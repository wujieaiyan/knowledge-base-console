package com.kbconsole.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaService {
    private static final long AVAILABILITY_TTL_MS = 30_000;

    private final LlmProperties properties;
    private final JsonHttpClient http;
    private final ObjectMapper objectMapper;

    private volatile boolean available;
    private volatile long checkedAt;
    private volatile String lastError;

    public record Context(int index, String documentName, String titlePath, Integer pageNo, String content) {
    }

    public record Turn(String role, String content) {
    }

    public record Answer(boolean generated, String text, String error, String model, long tookMs) {
        public static Answer skipped(String model, String error) {
            return new Answer(false, null, error, model, 0);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logConfiguration() {
        if (!properties.isEnabled()) {
            log.info("Ollama 生成已关闭（kb.llm.enabled=false）");
            return;
        }
        log.info("Ollama 配置：baseUrl={}, model={}, numCtx={}, temperature={}",
                baseUrl(), properties.getModel(), properties.getNumCtx(), properties.getTemperature());
        if (properties.isVerifyOnStartup()) {
            if (check(false)) {
                log.info("Ollama 模型可用：{}", properties.getModel());
            } else {
                log.warn("Ollama 暂不可用：{}", lastError);
            }
        }
    }

    public Answer generate(String query, List<Context> contexts) {
        return generate(query, contexts, List.of());
    }

    public Answer generate(String query, List<Context> contexts, List<Turn> history) {
        long started = System.currentTimeMillis();
        if (!properties.isEnabled()) {
            return Answer.skipped(properties.getModel(), "Ollama 生成未启用");
        }
        if (contexts == null || contexts.isEmpty()) {
            return Answer.skipped(properties.getModel(), "没有可用于生成的检索片段");
        }
        if (!check(false)) {
            return Answer.skipped(properties.getModel(), lastError);
        }

        List<Context> selected = contexts.stream()
                .limit(Math.max(1, properties.getMaxContextChunks()))
                .toList();
        String prompt = buildPrompt(query, selected);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt()));
        for (Turn turn : historyMessages(history)) {
            messages.add(Map.of("role", turn.role(), "content", truncate(turn.content(), 500)));
        }
        messages.add(Map.of("role", "user", "content", prompt));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("stream", false);
        body.put("messages", messages);
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("num_ctx", properties.getNumCtx());
        options.put("temperature", properties.getTemperature());
        body.put("options", options);
        if (properties.isThink()) {
            body.put("think", true);
        }

        try {
            JsonHttpClient.HttpResult result = http.postJson(baseUrl() + "/api/chat", body,
                    Duration.ofSeconds(Math.max(5, properties.getTimeoutSeconds())));
            if (!result.ok()) {
                String error = "Ollama 调用失败（HTTP " + result.status() + "）";
                log.warn("{} body={}", error, abbreviate(result.body()));
                return new Answer(false, null, error, properties.getModel(),
                        System.currentTimeMillis() - started);
            }
            JsonNode root = objectMapper.readTree(result.body());
            if (root.hasNonNull("error")) {
                String error = "Ollama 返回错误：" + root.get("error").asText();
                log.warn(error);
                return new Answer(false, null, error, properties.getModel(),
                        System.currentTimeMillis() - started);
            }
            String content = root.path("message").path("content").asText("").trim();
            if (content.isEmpty()) {
                String error = "Ollama 返回了空回答";
                log.warn(error);
                return new Answer(false, null, error, properties.getModel(),
                        System.currentTimeMillis() - started);
            }
            long took = System.currentTimeMillis() - started;
            log.info("Ollama 生成完成：model={}, contexts={}, queryChars={}, answerChars={}, took={}ms",
                    properties.getModel(), selected.size(), query.length(), content.length(), took);
            return new Answer(true, content, null, properties.getModel(), took);
        } catch (Exception ex) {
            String error = friendlyError(ex);
            log.warn("Ollama 生成失败：{}", error);
            return new Answer(false, null, error, properties.getModel(),
                    System.currentTimeMillis() - started);
        }
    }

    private boolean check(boolean force) {
        if (!properties.isEnabled()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (!force && checkedAt > 0 && now - checkedAt < AVAILABILITY_TTL_MS) {
            return available;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            if (!force && checkedAt > 0 && now - checkedAt < AVAILABILITY_TTL_MS) {
                return available;
            }
            try {
                JsonHttpClient.HttpResult result = http.get(baseUrl() + "/api/tags", Duration.ofSeconds(3));
                if (!result.ok()) {
                    available = false;
                    lastError = "Ollama 服务返回 HTTP " + result.status();
                } else {
                    JsonNode models = objectMapper.readTree(result.body()).path("models");
                    boolean found = false;
                    for (JsonNode model : models) {
                        if (matches(model.path("name").asText(""))) {
                            found = true;
                            break;
                        }
                    }
                    available = found;
                    lastError = found ? null
                            : "Ollama 未安装模型 " + properties.getModel()
                            + "，请先执行：ollama pull " + properties.getModel();
                }
            } catch (Exception ex) {
                available = false;
                lastError = friendlyError(ex);
            }
            checkedAt = System.currentTimeMillis();
            return available;
        }
    }

    private boolean matches(String name) {
        String target = properties.getModel();
        if (name.equals(target)) {
            return true;
        }
        return target.indexOf(':') < 0 && name.startsWith(target + ":");
    }

    private String friendlyError(Exception ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        String lower = message.toLowerCase();
        if (lower.contains("connection refused") || lower.contains("connect")
                || lower.contains("网络请求失败")) {
            return "无法连接 Ollama（" + baseUrl() + "），请先启动服务：ollama serve";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "Ollama 响应超时，请稍后重试或调大 kb.llm.timeout-seconds";
        }
        return message.isBlank() ? "Ollama 调用失败" : message;
    }

    private String systemPrompt() {
        return "你是企业知识库问答助手。请严格依据用户提供的资料回答，不要编造资料中没有的信息；"
                + "资料不足时直接说明缺少哪些信息。引用资料时在相应句子末尾标注 [编号]。"
                + "使用简体中文，条理清晰，只输出纯文本，不要使用 Markdown 标题、表格或代码块。";
    }

    private List<Turn> historyMessages(List<Turn> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<Turn> valid = history.stream()
                .filter(turn -> turn != null && turn.content() != null && !turn.content().isBlank())
                .filter(turn -> "user".equals(turn.role()) || "assistant".equals(turn.role()))
                .toList();
        int from = Math.max(0, valid.size() - 12);
        return valid.subList(from, valid.size());
    }

    private String buildPrompt(String query, List<Context> contexts) {
        int budget = Math.max(500, properties.getMaxContextChars());
        int perContext = Math.max(200, budget / Math.max(1, contexts.size()));
        StringBuilder prompt = new StringBuilder();
        prompt.append("问题：").append(query).append("\n\n资料：\n");
        for (Context context : contexts) {
            prompt.append('[').append(context.index()).append("] 文档：")
                    .append(nullToEmpty(context.documentName()));
            if (context.titlePath() != null && !context.titlePath().isBlank()) {
                prompt.append(" | 路径：").append(context.titlePath());
            }
            if (context.pageNo() != null) {
                prompt.append(" | 第 ").append(context.pageNo()).append(" 页");
            }
            prompt.append('\n').append(truncate(context.content(), perContext)).append("\n\n");
        }
        prompt.append("请基于以上资料回答问题，并在引用处标注来源编号。"
                + "对话历史仅用于理解追问中的指代，事实依据必须来自以上资料。");
        return prompt.toString();
    }

    private String baseUrl() {
        String url = properties.getBaseUrl() == null ? "http://localhost:11434" : properties.getBaseUrl().trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 300 ? value : value.substring(0, 300);
    }
}
