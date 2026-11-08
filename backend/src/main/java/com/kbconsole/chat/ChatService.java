package com.kbconsole.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbconsole.common.ApiException;
import com.kbconsole.common.CurrentUser;
import com.kbconsole.common.ErrorCodes;
import com.kbconsole.common.UserContext;
import com.kbconsole.integration.OllamaService;
import com.kbconsole.member.PermissionService;
import com.kbconsole.retrieval.RetrievalService;
import com.kbconsole.series.KbSeries;
import com.kbconsole.series.KbSeriesRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {
    private static final int HISTORY_MESSAGES = 12;
    private static final String DEFAULT_TITLE = "新会话";

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final KbSeriesRepository seriesRepository;
    private final PermissionService permissionService;
    private final RetrievalService retrievalService;
    private final ObjectMapper objectMapper;

    public record CreateSessionRequest(@NotNull Long seriesId) {
    }

    public record RenameSessionRequest(@NotBlank @Size(max = 120) String title) {
    }

    public record SendMessageRequest(@NotBlank @Size(max = 200) String content) {
    }

    public record SessionSummary(Long id, Long seriesId, String seriesName, boolean seriesAvailable,
                                 String title, long messageCount, Instant createdAt, Instant updatedAt) {
    }

    public record SessionView(Long id, Long seriesId, String seriesName, boolean seriesAvailable,
                              String title, Instant createdAt, Instant updatedAt) {
    }

    public record MessageMeta(String engine, String model, Integer totalCandidates, Long retrievalTookMs,
                              Long llmTookMs, boolean noAnswer, boolean llmUsed, String error) {
    }

    public record MessageView(Long id, String role, String content, List<RetrievalService.Citation> citations,
                              MessageMeta meta, Instant createdAt) {
    }

    public record SendMessageResponse(Long sessionId, String sessionTitle, MessageView userMessage,
                                      MessageView assistantMessage) {
    }

    public List<SessionSummary> listSessions() {
        CurrentUser user = UserContext.require();
        List<ChatSession> sessions = sessionRepository.findByUserIdOrderByUpdatedAtDescIdDesc(user.id());
        if (sessions.isEmpty()) {
            return List.of();
        }
        Map<Long, KbSeries> seriesById = seriesRepository.findAllById(
                        sessions.stream().map(ChatSession::getSeriesId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(KbSeries::getId, Function.identity()));
        Map<Long, Long> counts = messageCounts(sessions.stream().map(ChatSession::getId).toList());
        return sessions.stream()
                .map(session -> {
                    KbSeries series = seriesById.get(session.getSeriesId());
                    return new SessionSummary(session.getId(), session.getSeriesId(),
                            series == null ? "知识库已删除" : series.getName(),
                            available(series), session.getTitle(),
                            counts.getOrDefault(session.getId(), 0L),
                            session.getCreatedAt(), session.getUpdatedAt());
                })
                .toList();
    }

    public SessionView createSession(Long seriesId) {
        CurrentUser user = UserContext.require();
        KbSeries series = requireActiveSeries(seriesId);
        permissionService.requireRead(seriesId);
        ChatSession session = new ChatSession();
        session.setUserId(user.id());
        session.setSeriesId(seriesId);
        session.setTitle(DEFAULT_TITLE);
        session = sessionRepository.save(session);
        return view(session, series);
    }

    public SessionView renameSession(Long sessionId, String title) {
        CurrentUser user = UserContext.require();
        ChatSession session = ownedSession(sessionId, user.id());
        String value = title == null ? "" : title.trim();
        if (value.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "会话名称不能为空");
        }
        session.setTitle(value.length() > 120 ? value.substring(0, 120) : value);
        session.setUpdatedAt(Instant.now());
        session = sessionRepository.save(session);
        KbSeries series = seriesRepository.findById(session.getSeriesId()).orElse(null);
        return view(session, series);
    }

    @Transactional
    public void deleteSession(Long sessionId) {
        CurrentUser user = UserContext.require();
        ChatSession session = ownedSession(sessionId, user.id());
        messageRepository.deleteBySessionId(session.getId());
        sessionRepository.delete(session);
    }

    public List<MessageView> messages(Long sessionId) {
        CurrentUser user = UserContext.require();
        ChatSession session = ownedSession(sessionId, user.id());
        return messageRepository.findBySessionIdOrderByIdAsc(session.getId()).stream()
                .map(this::messageView)
                .toList();
    }

    @Transactional
    public SendMessageResponse sendMessage(Long sessionId, String content) {
        CurrentUser user = UserContext.require();
        ChatSession session = ownedSession(sessionId, user.id());
        String question = content == null ? "" : content.trim();
        if (question.isEmpty() || question.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "问题需为 1-200 个字符");
        }
        KbSeries series = requireActiveSeries(session.getSeriesId());
        permissionService.requireRead(session.getSeriesId());

        List<OllamaService.Turn> history = history(session.getId());
        RetrievalService.SearchResponse response = retrievalService.search(session.getSeriesId(),
                new RetrievalService.SearchRequest(question, null, null, null, null, true), history);

        boolean noAnswer = response.citations().isEmpty();
        String answerText = answerText(response, noAnswer);
        long existing = messageRepository.countBySessionId(session.getId());

        ChatMessage userMessage = new ChatMessage();
        userMessage.setSessionId(session.getId());
        userMessage.setRole("USER");
        userMessage.setContent(question);
        userMessage = messageRepository.save(userMessage);

        ChatMessage assistant = new ChatMessage();
        assistant.setSessionId(session.getId());
        assistant.setRole("ASSISTANT");
        assistant.setContent(answerText);
        assistant.setCitationsJson(writeJson(response.citations()));
        assistant.setMetaJson(writeJson(new MessageMeta(response.retrievalEngine(), response.llmModel(),
                response.totalCandidates(), response.retrievalTookMs(), response.llmTookMs(), noAnswer,
                response.llmUsed(), response.llmError())));
        assistant = messageRepository.save(assistant);

        if (existing == 0 && DEFAULT_TITLE.equals(session.getTitle())) {
            session.setTitle(titleOf(question));
        }
        session.setUpdatedAt(Instant.now());
        sessionRepository.save(session);

        log.info("Chat message session={} user={} series={} citations={} llmUsed={} answerChars={}",
                session.getId(), user.id(), session.getSeriesId(), response.citations().size(),
                response.llmUsed(), answerText.length());
        return new SendMessageResponse(session.getId(), session.getTitle(),
                messageView(userMessage), messageView(assistant));
    }

    private List<OllamaService.Turn> history(Long sessionId) {
        List<ChatMessage> recent = messageRepository.findTop12BySessionIdOrderByIdDesc(sessionId);
        if (recent.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> ordered = new ArrayList<>(recent);
        Collections.reverse(ordered);
        int from = Math.max(0, ordered.size() - HISTORY_MESSAGES);
        List<OllamaService.Turn> turns = new ArrayList<>();
        for (ChatMessage message : ordered.subList(from, ordered.size())) {
            String role = "USER".equals(message.getRole()) ? "user" : "assistant";
            turns.add(new OllamaService.Turn(role, message.getContent()));
        }
        return turns;
    }

    private String answerText(RetrievalService.SearchResponse response, boolean noAnswer) {
        if (response.answer() != null && !response.answer().isBlank()) {
            return response.answer().trim();
        }
        if (noAnswer) {
            return "未在知识库中找到与该问题相关的内容，建议换一种问法，或先补充相关文档。";
        }
        String error = response.llmError();
        return "已检索到相关资料，但生成服务暂不可用"
                + (error == null || error.isBlank() ? "" : "（" + error + "）")
                + "，请先查看下方引用内容。";
    }

    private String titleOf(String question) {
        String flat = question.replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) {
            return DEFAULT_TITLE;
        }
        return flat.length() > 24 ? flat.substring(0, 24) : flat;
    }

    private Map<Long, Long> messageCounts(List<Long> sessionIds) {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : messageRepository.countBySessionIds(sessionIds)) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }

    private ChatSession ownedSession(Long sessionId, Long userId) {
        return sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.SESSION_NOT_FOUND,
                        "会话不存在或无权访问"));
    }

    private KbSeries requireActiveSeries(Long seriesId) {
        if (seriesId == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.SERIES_NOT_FOUND, "知识库不存在或无权访问");
        }
        return seriesRepository.findByIdAndDeletedAtIsNull(seriesId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.SERIES_NOT_FOUND,
                        "知识库不存在或无权访问"));
    }

    private boolean available(KbSeries series) {
        return series != null && series.getDeletedAt() == null;
    }

    private SessionView view(ChatSession session, KbSeries series) {
        return new SessionView(session.getId(), session.getSeriesId(),
                series == null ? "知识库已删除" : series.getName(),
                available(series), session.getTitle(), session.getCreatedAt(), session.getUpdatedAt());
    }

    private MessageView messageView(ChatMessage message) {
        return new MessageView(message.getId(), message.getRole(), message.getContent(),
                readCitations(message.getCitationsJson()), readMeta(message.getMetaJson()), message.getCreatedAt());
    }

    private List<RetrievalService.Citation> readCitations(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<RetrievalService.Citation>>() {
            });
        } catch (Exception ex) {
            log.warn("会话引用解析失败：{}", ex.getMessage());
            return List.of();
        }
    }

    private MessageMeta readMeta(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MessageMeta.class);
        } catch (Exception ex) {
            log.warn("会话元数据解析失败：{}", ex.getMessage());
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            log.warn("会话数据序列化失败：{}", ex.getMessage());
            return null;
        }
    }
}
