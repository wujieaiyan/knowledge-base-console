package com.kbconsole.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbconsole.common.ChunkLevel;
import com.kbconsole.document.KbDocument;
import com.kbconsole.ingest.KbChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ElasticsearchService {
    private static final long AVAILABILITY_TTL_MS = 30_000;

    private final ElasticsearchProperties properties;
    private final JsonHttpClient http;
    private final ObjectMapper objectMapper;

    private volatile boolean available;
    private volatile long checkedAt;
    private volatile String lastError;

    public record Hit(Long chunkId, double score, double vectorScore, double bm25Score) {
    }

    public record SearchResult(List<Hit> hits, int candidateCount) {
    }

    public record IndexResult(boolean success, int count, String message) {
    }

    private record EsDocument(Long id, Map<String, Object> source) {
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    public boolean available() {
        if (!properties.isEnabled()) {
            return false;
        }
        if (available) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (checkedAt > 0 && now - checkedAt < AVAILABILITY_TTL_MS) {
            return false;
        }
        return ensureIndex();
    }

    public boolean ensureIndex() {
        if (!properties.isEnabled()) {
            available = false;
            return false;
        }
        synchronized (this) {
            if (available) {
                return true;
            }
            long now = System.currentTimeMillis();
            if (checkedAt > 0 && now - checkedAt < AVAILABILITY_TTL_MS) {
                return false;
            }
            try {
                JsonHttpClient.HttpResult head = http.head(indexUrl(), timeout());
                if (head.status() == 200) {
                    available = true;
                    lastError = null;
                } else if (head.status() == 404) {
                    JsonHttpClient.HttpResult created = http.putJson(indexUrl(), mapping(), timeout());
                    boolean alreadyExists = created.status() == 400
                            && created.body() != null
                            && created.body().contains("resource_already_exists_exception");
                    if (created.ok() || alreadyExists) {
                        available = true;
                        lastError = null;
                        log.info("Elasticsearch 索引已创建：{} (dimensions={})",
                                properties.getIndex(), properties.getDimensions());
                    } else {
                        available = false;
                        lastError = "创建索引失败（HTTP " + created.status() + "）："
                                + abbreviate(created.body());
                    }
                } else {
                    available = false;
                    lastError = "Elasticsearch 返回 HTTP " + head.status();
                }
            } catch (Exception ex) {
                available = false;
                lastError = friendlyError(ex);
            }
            checkedAt = System.currentTimeMillis();
            if (!available) {
                log.warn("Elasticsearch 不可用，检索将回退到本地索引：{}", lastError);
            }
            return available;
        }
    }

    public long count() {
        if (!available()) {
            return -1;
        }
        try {
            JsonHttpClient.HttpResult result = http.get(indexUrl() + "/_count", timeout());
            if (result.ok()) {
                return objectMapper.readTree(result.body()).path("count").asLong();
            }
            markUnavailable("查询索引数量失败（HTTP " + result.status() + "）");
        } catch (Exception ex) {
            markUnavailable(friendlyError(ex));
        }
        return -1;
    }

    public IndexResult replaceDocument(KbDocument document, List<KbChunk> chunks) {
        return indexDocument(document, chunks, true);
    }

    public IndexResult indexDocument(KbDocument document, List<KbChunk> chunks) {
        return indexDocument(document, chunks, false);
    }

    private IndexResult indexDocument(KbDocument document, List<KbChunk> chunks, boolean deleteExisting) {
        if (!available()) {
            return new IndexResult(false, 0, lastError == null ? "Elasticsearch 不可用" : lastError);
        }
        long started = System.currentTimeMillis();
        if (deleteExisting && !deleteByDocument(document.getId())) {
            return new IndexResult(false, 0, lastError == null ? "清理旧索引失败" : lastError);
        }
        List<EsDocument> documents = new ArrayList<>();
        for (KbChunk chunk : chunks) {
            if (chunk.getChunkLevel() == ChunkLevel.PARENT) {
                continue;
            }
            if (chunk.getVector() == null || chunk.getVector().isBlank()) {
                continue;
            }
            double[] vector;
            try {
                vector = objectMapper.readValue(chunk.getVector(), double[].class);
            } catch (Exception ex) {
                log.warn("跳过无法解析向量的切片 #{}：{}", chunk.getId(), ex.getMessage());
                continue;
            }
            if (vector.length != properties.getDimensions()) {
                log.warn("跳过维度不一致的切片 #{}：vector={}, expected={}",
                        chunk.getId(), vector.length, properties.getDimensions());
                continue;
            }
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("chunkId", chunk.getId());
            source.put("seriesId", chunk.getSeriesId());
            source.put("documentId", chunk.getDocumentId());
            source.put("seq", chunk.getSeq());
            source.put("chunkLevel", chunk.getChunkLevel().name());
            source.put("content", chunk.getContent());
            source.put("titlePath", chunk.getTitlePath());
            source.put("pageNo", chunk.getPageNo());
            source.put("status", chunk.getStatus());
            source.put("documentName", document.getName());
            source.put("documentExt", document.getExt());
            source.put("vector", vector);
            documents.add(new EsDocument(chunk.getId(), source));
        }
        if (documents.isEmpty()) {
            return new IndexResult(true, 0, "没有可索引的切片");
        }
        int batchSize = Math.max(1, properties.getBulkSize());
        int indexed = 0;
        for (int start = 0; start < documents.size(); start += batchSize) {
            int end = Math.min(start + batchSize, documents.size());
            if (!bulkIndex(documents.subList(start, end))) {
                return new IndexResult(false, indexed, lastError == null ? "批量写入失败" : lastError);
            }
            indexed += end - start;
        }
        log.info("Elasticsearch 写入完成：document={}, chunks={}, took={}ms",
                document.getId(), indexed, System.currentTimeMillis() - started);
        return new IndexResult(true, indexed, null);
    }

    public boolean deleteByDocument(Long documentId) {
        return deleteBy("documentId", documentId);
    }

    public boolean deleteBySeries(Long seriesId) {
        return deleteBy("seriesId", seriesId);
    }

    public SearchResult search(Long seriesId, String query, double[] queryVector, boolean hybrid,
                               int candidateLimit, double threshold, double vectorWeight) {
        if (!available()) {
            return null;
        }
        if (queryVector == null || queryVector.length != properties.getDimensions()) {
            markUnavailable("查询向量维度与索引不一致，已回退到本地检索");
            return null;
        }
        try {
            int size = Math.max(1, Math.min(500, candidateLimit));
            Map<Long, Double> vectorScores = vectorSearch(seriesId, queryVector, size);
            Map<Long, Double> bm25Scores = hybrid ? bm25Search(seriesId, query, size) : Map.of();
            double maxBm25 = bm25Scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
            Set<Long> ids = new LinkedHashSet<>(vectorScores.keySet());
            ids.addAll(bm25Scores.keySet());
            List<Hit> hits = new ArrayList<>();
            for (Long id : ids) {
                double vectorScore = clamp(vectorScores.getOrDefault(id, 0.0));
                double bm25Score = maxBm25 == 0 ? 0 : bm25Scores.getOrDefault(id, 0.0) / maxBm25;
                double score = hybrid
                        ? vectorWeight * vectorScore + (1 - vectorWeight) * bm25Score
                        : vectorScore;
                if (score >= threshold) {
                    hits.add(new Hit(id, score, vectorScore, bm25Score));
                }
            }
            hits.sort(Comparator.comparingDouble(Hit::score).reversed());
            if (hits.size() > size) {
                hits = new ArrayList<>(hits.subList(0, size));
            }
            return new SearchResult(hits, ids.size());
        } catch (Exception ex) {
            markUnavailable(friendlyError(ex));
            return null;
        }
    }

    private Map<Long, Double> vectorSearch(Long seriesId, double[] queryVector, int size) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", size);
        body.put("track_total_hits", false);
        body.put("_source", List.of("chunkId"));
        Map<String, Object> scriptScore = new LinkedHashMap<>();
        scriptScore.put("query", Map.of("bool", Map.of("filter",
                List.of(Map.of("term", Map.of("seriesId", seriesId))))));
        scriptScore.put("script", Map.of(
                "source", "cosineSimilarity(params.query_vector, 'vector') + 1.0",
                "params", Map.of("query_vector", queryVector)
        ));
        body.put("query", Map.of("script_score", scriptScore));

        JsonNode root = search(body);
        Map<Long, Double> scores = new LinkedHashMap<>();
        for (JsonNode hit : root.path("hits").path("hits")) {
            long chunkId = hit.path("_source").path("chunkId").asLong(hit.path("_id").asLong(0));
            if (chunkId > 0) {
                scores.put(chunkId, clamp(hit.path("_score").asDouble() - 1.0));
            }
        }
        return scores;
    }

    private Map<Long, Double> bm25Search(Long seriesId, String query, int size) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", size);
        body.put("track_total_hits", false);
        body.put("_source", List.of("chunkId"));
        body.put("query", Map.of("bool", Map.of(
                "filter", List.of(Map.of("term", Map.of("seriesId", seriesId))),
                "must", List.of(Map.of("multi_match", Map.of(
                        "query", query,
                        "fields", List.of("content", "titlePath^2")
                )))
        )));

        JsonNode root = search(body);
        Map<Long, Double> scores = new LinkedHashMap<>();
        for (JsonNode hit : root.path("hits").path("hits")) {
            long chunkId = hit.path("_source").path("chunkId").asLong(hit.path("_id").asLong(0));
            if (chunkId > 0) {
                scores.put(chunkId, hit.path("_score").asDouble());
            }
        }
        return scores;
    }

    private JsonNode search(Map<String, Object> body) throws Exception {
        JsonHttpClient.HttpResult result = http.postJson(indexUrl() + "/_search", body, timeout());
        if (!result.ok()) {
            throw new IntegrationException("Elasticsearch 查询失败（HTTP " + result.status() + "）："
                    + abbreviate(result.body()));
        }
        return objectMapper.readTree(result.body());
    }

    private boolean bulkIndex(List<EsDocument> documents) {
        StringBuilder body = new StringBuilder(documents.size() * 512);
        for (EsDocument document : documents) {
            body.append("{\"index\":{\"_id\":\"").append(document.id()).append("\"}}\n");
            try {
                body.append(objectMapper.writeValueAsString(document.source())).append('\n');
            } catch (JsonProcessingException ex) {
                markUnavailable("索引文档序列化失败：" + ex.getOriginalMessage());
                return false;
            }
        }
        String url = indexUrl() + "/_bulk" + (properties.isRefresh() ? "?refresh=true" : "");
        try {
            JsonHttpClient.HttpResult result = http.postRaw(url, body.toString(),
                    "application/x-ndjson", bulkTimeout());
            if (!result.ok()) {
                markUnavailable("Elasticsearch 批量写入失败（HTTP " + result.status() + "）");
                return false;
            }
            JsonNode root = objectMapper.readTree(result.body());
            if (root.path("errors").asBoolean(false)) {
                markUnavailable("Elasticsearch 批量写入存在错误：" + firstBulkError(root));
                return false;
            }
            return true;
        } catch (Exception ex) {
            markUnavailable(friendlyError(ex));
            return false;
        }
    }

    private boolean deleteBy(String field, Long id) {
        if (!available()) {
            return false;
        }
        try {
            Map<String, Object> body = Map.of("query", Map.of("term", Map.of(field, id)));
            String url = indexUrl() + "/_delete_by_query?conflicts=proceed"
                    + (properties.isRefresh() ? "&refresh=true" : "");
            JsonHttpClient.HttpResult result = http.postJson(url, body, timeout());
            if (result.ok() || result.status() == 404) {
                return true;
            }
            markUnavailable("Elasticsearch 删除索引失败（HTTP " + result.status() + "）");
            return false;
        } catch (Exception ex) {
            markUnavailable(friendlyError(ex));
            return false;
        }
    }

    private Map<String, Object> mapping() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("chunkId", Map.of("type", "long"));
        fields.put("seriesId", Map.of("type", "long"));
        fields.put("documentId", Map.of("type", "long"));
        fields.put("seq", Map.of("type", "integer"));
        fields.put("chunkLevel", Map.of("type", "keyword"));
        fields.put("status", Map.of("type", "keyword"));
        fields.put("documentName", Map.of("type", "keyword"));
        fields.put("documentExt", Map.of("type", "keyword"));
        fields.put("pageNo", Map.of("type", "integer"));
        fields.put("content", Map.of("type", "text", "analyzer", "cjk_text"));
        fields.put("titlePath", Map.of("type", "text", "analyzer", "cjk_text"));
        fields.put("vector", Map.of("type", "dense_vector", "dims", properties.getDimensions()));

        Map<String, Object> analysis = Map.of(
                "filter", Map.of(
                        "cjk_bigram_filter", Map.of(
                                "type", "cjk_bigram",
                                "output_unigrams", true
                        )
                ),
                "analyzer", Map.of(
                        "cjk_text", Map.of(
                                "type", "custom",
                                "tokenizer", "standard",
                                "filter", List.of("lowercase", "cjk_bigram_filter")
                        )
                )
        );
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("number_of_shards", 1);
        settings.put("number_of_replicas", 0);
        settings.put("analysis", analysis);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("settings", settings);
        body.put("mappings", Map.of("properties", fields));
        return body;
    }

    private String firstBulkError(JsonNode root) {
        for (JsonNode item : root.path("items")) {
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = item.fields();
            while (fields.hasNext()) {
                JsonNode error = fields.next().getValue().path("error");
                if (!error.isMissingNode() && !error.isNull()) {
                    String reason = error.path("reason").asText(error.toString());
                    return abbreviate(reason);
                }
            }
        }
        return "未知错误";
    }

    private void markUnavailable(String error) {
        available = false;
        checkedAt = System.currentTimeMillis();
        lastError = error;
    }

    private String indexUrl() {
        return baseUrl() + "/" + properties.getIndex();
    }

    private String baseUrl() {
        String url = properties.getBaseUrl() == null ? "http://localhost:9200" : properties.getBaseUrl().trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private Duration timeout() {
        return Duration.ofSeconds(Math.max(3, properties.getRequestTimeoutSeconds()));
    }

    private Duration bulkTimeout() {
        return Duration.ofSeconds(Math.max(30, properties.getRequestTimeoutSeconds()));
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private String friendlyError(Exception ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        String lower = message.toLowerCase();
        if (lower.contains("connection refused") || lower.contains("connect")
                || lower.contains("网络请求失败")) {
            return "无法连接 Elasticsearch（" + baseUrl() + "），请先启动 Elasticsearch，"
                    + "或设置 kb.elasticsearch.enabled=false 使用本地索引";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "Elasticsearch 请求超时，请检查服务状态或调大 kb.elasticsearch.request-timeout-seconds";
        }
        return message.isBlank() ? "Elasticsearch 调用失败" : message;
    }

    private String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 300 ? value : value.substring(0, 300);
    }
}
