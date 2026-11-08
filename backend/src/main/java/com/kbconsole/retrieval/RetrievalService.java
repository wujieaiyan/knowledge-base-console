package com.kbconsole.retrieval;

import com.kbconsole.common.ApiException;
import com.kbconsole.common.ChunkLevel;
import com.kbconsole.common.DocStatus;
import com.kbconsole.common.ErrorCodes;
import com.kbconsole.config.ConfigService;
import com.kbconsole.config.KbConfigVersion;
import com.kbconsole.config.dto.ConfigModels.RetrievalConfig;
import com.kbconsole.document.KbDocument;
import com.kbconsole.document.KbDocumentRepository;
import com.kbconsole.ingest.EmbeddingService;
import com.kbconsole.ingest.KbChunk;
import com.kbconsole.ingest.KbChunkRepository;
import com.kbconsole.integration.ElasticsearchService;
import com.kbconsole.integration.OllamaService;
import com.kbconsole.member.PermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {
    private final KbChunkRepository chunkRepository;
    private final KbDocumentRepository documentRepository;
    private final ConfigService configService;
    private final PermissionService permissionService;
    private final EmbeddingService embeddingService;
    private final ElasticsearchService elasticsearchService;
    private final OllamaService ollamaService;

    public record SearchRequest(String query, Integer topK, Double threshold, Boolean hybrid, Boolean rerank,
                                Boolean generate) {
    }

    public record Citation(Long chunkId, Long documentId, String documentName, String documentExt,
                           Integer pageNo, String titlePath, String content, String parentContent,
                           String chunkLevel, int seq, double score) {
    }

    public record SearchResponse(String query, String answer, boolean noAnswer, int totalCandidates,
                                 int returned, long tookMs, String indexStrategy, boolean hybrid,
                                 boolean rerank, String retrievalEngine, boolean llmUsed,
                                 String llmModel, String llmError, long retrievalTookMs,
                                 long llmTookMs, List<Citation> citations) {
    }

    private record ScoredHit(KbChunk chunk, double score, double vectorScore, double bm25Score) {
    }

    private record SearchOutcome(List<ScoredHit> hits, int totalCandidates, String engine) {
    }

    public SearchResponse search(Long seriesId, SearchRequest request) {
        return search(seriesId, request, List.of());
    }

    public SearchResponse search(Long seriesId, SearchRequest request, List<OllamaService.Turn> history) {
        permissionService.requireRead(seriesId);
        long started = System.currentTimeMillis();
        String query = request.query() == null ? "" : request.query().trim();
        if (query.isEmpty() || query.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "查询内容需为 1-200 个字符");
        }
        List<String> queryTokens = embeddingService.tokens(query);
        if (queryTokens.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "查询内容不包含可检索的词语");
        }

        KbConfigVersion version = configService.entityForTask(seriesId, null);
        RetrievalConfig config = version == null
                ? com.kbconsole.config.dto.ConfigModels.defaultRetrieval() : configService.retrieval(version);
        String indexStrategy = version == null ? "HYBRID" : version.getIndexStrategy().name();
        boolean hybrid = "VECTOR".equals(indexStrategy) ? false
                : (request.hybrid() != null ? request.hybrid() : config.isHybridEnabled());
        boolean rerank = request.rerank() != null ? request.rerank() : config.isRerankEnabled();
        boolean generate = request.generate() != null ? request.generate() : true;
        double threshold = request.threshold() == null ? config.getSimilarityThreshold() : request.threshold();
        threshold = Math.max(0, Math.min(1, threshold));
        int recallLimit = Math.max(1, config.getRecallTopK());
        int resultLimit = request.topK() != null
                ? Math.max(1, Math.min(20, request.topK()))
                : Math.max(1, Math.min(config.getRerankTopN(), recallLimit));

        List<KbChunk> allChunks = chunkRepository.findBySeriesIdAndStatus(seriesId, "ACTIVE");
        if (allChunks.isEmpty()) {
            return emptyResponse(query, indexStrategy, hybrid, rerank, started);
        }
        Map<Long, KbDocument> documents = documentRepository.findAllById(
                        allChunks.stream().map(KbChunk::getDocumentId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(KbDocument::getId, Function.identity()));
        List<KbChunk> candidates = allChunks.stream()
                .filter(chunk -> chunk.getChunkLevel() != ChunkLevel.PARENT)
                .filter(chunk -> {
                    KbDocument document = documents.get(chunk.getDocumentId());
                    return document != null && document.getStatus() == DocStatus.READY
                            && document.getDeletedAt() == null;
                })
                .toList();
        if (candidates.isEmpty()) {
            return emptyResponse(query, indexStrategy, hybrid, rerank, started);
        }

        double[] queryVector = embeddingService.embed(query);
        double vectorWeight = Math.max(0, Math.min(1, config.getVectorWeight()));
        int candidateLimit = Math.min(200, Math.max(recallLimit * 3, 30));

        SearchOutcome outcome = searchWithElasticsearch(seriesId, query, queryVector, documents, hybrid,
                threshold, vectorWeight, recallLimit, candidateLimit);
        if (outcome == null || (outcome.hits().isEmpty() && outcome.totalCandidates() == 0)) {
            outcome = searchLocally(queryTokens, queryVector, candidates, hybrid, threshold,
                    vectorWeight, recallLimit);
        }

        List<ScoredHit> hits = outcome.hits();
        if (rerank && !hits.isEmpty()) {
            hits = rerank(query, queryTokens, hits);
        }
        List<ScoredHit> limited = hits.stream().limit(resultLimit).toList();
        Map<Long, KbChunk> byId = allChunks.stream()
                .collect(Collectors.toMap(KbChunk::getId, Function.identity()));
        List<Citation> citations = buildCitations(limited, documents, byId);
        long retrievalTook = System.currentTimeMillis() - started;

        OllamaService.Answer answer = null;
        if (generate && !citations.isEmpty()) {
            List<OllamaService.Context> contexts = new ArrayList<>();
            for (int i = 0; i < citations.size(); i++) {
                Citation citation = citations.get(i);
                String content = citation.parentContent() != null && !citation.parentContent().isBlank()
                        ? citation.parentContent() : citation.content();
                contexts.add(new OllamaService.Context(i + 1, citation.documentName(), citation.titlePath(),
                        citation.pageNo(), content));
            }
            answer = ollamaService.generate(query, contexts, history);
        }
        long totalTook = System.currentTimeMillis() - started;
        log.info("Retrieval series={} query=[{}] engine={} candidates={} returned={} retrieval={}ms llm={}ms answerChars={}",
                seriesId, query, outcome.engine(), outcome.totalCandidates(), citations.size(),
                retrievalTook, answer == null ? 0 : answer.tookMs(),
                answer == null || answer.text() == null ? 0 : answer.text().length());
        return new SearchResponse(query, answer == null ? null : answer.text(), citations.isEmpty(),
                outcome.totalCandidates(), citations.size(), totalTook, indexStrategy, hybrid, rerank,
                outcome.engine(), answer != null && answer.generated(),
                answer == null ? null : answer.model(), answer == null ? null : answer.error(),
                retrievalTook, answer == null ? 0 : answer.tookMs(), citations);
    }

    private SearchOutcome searchWithElasticsearch(Long seriesId, String query, double[] queryVector,
                                                  Map<Long, KbDocument> documents, boolean hybrid,
                                                  double threshold, double vectorWeight, int recallLimit,
                                                  int candidateLimit) {
        if (!elasticsearchService.available()) {
            return null;
        }
        ElasticsearchService.SearchResult result = elasticsearchService.search(seriesId, query, queryVector,
                hybrid, candidateLimit, threshold, vectorWeight);
        if (result == null) {
            return null;
        }
        if (result.hits().isEmpty()) {
            return new SearchOutcome(List.of(), result.candidateCount(), "ELASTICSEARCH");
        }
        Map<Long, KbChunk> chunkById = chunkRepository.findAllById(
                        result.hits().stream().map(ElasticsearchService.Hit::chunkId).toList())
                .stream().collect(Collectors.toMap(KbChunk::getId, Function.identity()));
        List<ScoredHit> hits = new ArrayList<>();
        for (ElasticsearchService.Hit hit : result.hits()) {
            KbChunk chunk = chunkById.get(hit.chunkId());
            if (chunk == null || chunk.getChunkLevel() == ChunkLevel.PARENT
                    || !"ACTIVE".equals(chunk.getStatus())) {
                continue;
            }
            KbDocument document = documents.get(chunk.getDocumentId());
            if (document == null || document.getDeletedAt() != null
                    || document.getStatus() != DocStatus.READY) {
                continue;
            }
            hits.add(new ScoredHit(chunk, hit.score(), hit.vectorScore(), hit.bm25Score()));
        }
        if (hits.size() > recallLimit) {
            hits = new ArrayList<>(hits.subList(0, recallLimit));
        }
        return new SearchOutcome(hits, result.candidateCount(), "ELASTICSEARCH");
    }

    private SearchOutcome searchLocally(List<String> queryTokens, double[] queryVector, List<KbChunk> candidates,
                                        boolean hybrid, double threshold, double vectorWeight, int recallLimit) {
        Map<Long, List<String>> chunkTokens = new HashMap<>();
        for (KbChunk chunk : candidates) {
            chunkTokens.put(chunk.getId(), embeddingService.tokens(chunk.getContent()));
        }
        Map<Long, Double> bm25 = hybrid ? bm25(queryTokens, candidates, chunkTokens) : Map.of();
        double maxBm25 = bm25.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        List<ScoredHit> scored = new ArrayList<>();
        for (KbChunk chunk : candidates) {
            double vectorScore = embeddingService.cosine(queryVector, embeddingService.fromJson(chunk.getVector()));
            double bm25Score = maxBm25 == 0 ? 0 : bm25.getOrDefault(chunk.getId(), 0.0) / maxBm25;
            double score = hybrid ? vectorWeight * vectorScore + (1 - vectorWeight) * bm25Score : vectorScore;
            if (score >= threshold) {
                scored.add(new ScoredHit(chunk, score, vectorScore, bm25Score));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredHit::score).reversed());
        if (scored.size() > recallLimit) {
            scored = new ArrayList<>(scored.subList(0, recallLimit));
        }
        return new SearchOutcome(scored, candidates.size(), "LOCAL");
    }

    private List<ScoredHit> rerank(String query, List<String> queryTokens, List<ScoredHit> hits) {
        Set<String> querySet = new HashSet<>(queryTokens);
        String queryLower = query.toLowerCase(Locale.ROOT);
        return hits.stream()
                .map(item -> {
                    Set<String> tokens = new HashSet<>(embeddingService.tokens(item.chunk().getContent()));
                    long matched = tokens.stream().filter(querySet::contains).count();
                    double coverage = querySet.isEmpty() ? 0 : (double) matched / querySet.size();
                    double titleBonus = item.chunk().getTitlePath() != null
                            && item.chunk().getTitlePath().toLowerCase(Locale.ROOT).contains(queryLower)
                            ? 0.05 : 0;
                    double finalScore = Math.min(1, 0.7 * item.score() + 0.3 * coverage + titleBonus);
                    return new ScoredHit(item.chunk(), finalScore, item.vectorScore(), item.bm25Score());
                })
                .sorted(Comparator.comparingDouble(ScoredHit::score).reversed())
                .toList();
    }

    private List<Citation> buildCitations(List<ScoredHit> limited, Map<Long, KbDocument> documents,
                                          Map<Long, KbChunk> byId) {
        List<Citation> citations = new ArrayList<>();
        for (ScoredHit item : limited) {
            KbChunk chunk = item.chunk();
            KbDocument document = documents.get(chunk.getDocumentId());
            String parentContent = null;
            if (chunk.getChunkLevel() == ChunkLevel.CHILD && chunk.getParentChunkId() != null) {
                KbChunk parent = byId.get(chunk.getParentChunkId());
                parentContent = parent == null ? null : truncate(parent.getContent(), 1200);
            }
            citations.add(new Citation(chunk.getId(), chunk.getDocumentId(),
                    document == null ? null : document.getName(), document == null ? null : document.getExt(),
                    chunk.getPageNo(), chunk.getTitlePath(), chunk.getContent(), parentContent,
                    chunk.getChunkLevel().name(), chunk.getSeq(), round(item.score())));
        }
        return citations;
    }

    private SearchResponse emptyResponse(String query, String indexStrategy, boolean hybrid, boolean rerank,
                                         long started) {
        long took = elapsed(started);
        String engine = elasticsearchService.enabled() && elasticsearchService.available()
                ? "ELASTICSEARCH" : "LOCAL";
        return new SearchResponse(query, null, true, 0, 0, took, indexStrategy, hybrid, rerank,
                engine, false, null, null, took, 0, List.of());
    }

    private Map<Long, Double> bm25(List<String> queryTokens, List<KbChunk> chunks,
                                   Map<Long, List<String>> chunkTokens) {
        int total = chunks.size();
        double avgLength = chunks.stream().mapToInt(c -> c.getContent().length()).average().orElse(1);
        Map<String, Long> documentFrequency = new HashMap<>();
        for (String token : new HashSet<>(queryTokens)) {
            long df = chunkTokens.values().stream().filter(tokens -> tokens.contains(token)).count();
            documentFrequency.put(token, df);
        }
        double k1 = 1.2;
        double b = 0.75;
        Map<Long, Double> scores = new LinkedHashMap<>();
        for (KbChunk chunk : chunks) {
            List<String> tokens = chunkTokens.getOrDefault(chunk.getId(), List.of());
            Map<String, Integer> frequencies = new HashMap<>();
            for (String token : tokens) {
                frequencies.merge(token, 1, Integer::sum);
            }
            double score = 0;
            for (String queryToken : new HashSet<>(queryTokens)) {
                int tf = frequencies.getOrDefault(queryToken, 0);
                if (tf == 0) {
                    continue;
                }
                long df = documentFrequency.getOrDefault(queryToken, 0L);
                double idf = Math.log(1 + (total - df + 0.5) / (df + 0.5));
                double denominator = tf + k1 * (1 - b + b * tokens.size() / Math.max(1, avgLength));
                score += idf * (tf * (k1 + 1)) / denominator;
            }
            scores.put(chunk.getId(), score);
        }
        return scores;
    }

    private long elapsed(long started) {
        return System.currentTimeMillis() - started;
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
