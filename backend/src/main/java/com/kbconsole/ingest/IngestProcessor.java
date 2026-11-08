package com.kbconsole.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbconsole.common.*;
import com.kbconsole.config.ConfigService;
import com.kbconsole.config.KbConfigVersion;
import com.kbconsole.config.dto.ConfigModels.ChunkConfig;
import com.kbconsole.config.dto.ConfigModels.ParseConfig;
import com.kbconsole.document.KbDocument;
import com.kbconsole.document.KbDocumentRepository;
import com.kbconsole.document.StorageService;
import com.kbconsole.integration.ElasticsearchService;
import com.kbconsole.series.SeriesService;
import com.kbconsole.task.KbIngestTask;
import com.kbconsole.task.KbIngestTaskRepository;
import com.kbconsole.task.KbTaskLog;
import com.kbconsole.task.KbTaskLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class IngestProcessor {
    private final KbIngestTaskRepository taskRepository;
    private final KbTaskLogRepository taskLogRepository;
    private final KbDocumentRepository documentRepository;
    private final KbChunkRepository chunkRepository;
    private final ConfigService configService;
    private final DocumentParserService parserService;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final ElasticsearchService elasticsearchService;
    private final StorageService storageService;
    private final SeriesService seriesService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    @Async("kbTaskExecutor")
    public void runAsync(Long taskId) {
        KbIngestTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null || task.getStatus() != TaskStatus.QUEUED) {
            return;
        }
        task.setStatus(TaskStatus.RUNNING);
        task.setStartedAt(Instant.now());
        task.setProgress(1);
        task.setStage("START");
        taskRepository.save(task);
        writeLog(taskId, "START", "INFO", "任务开始执行，类型 " + task.getType().name(), null);
        log.info("Task #{} started: type={}, series={}, document={}", taskId, task.getType(),
                task.getSeriesId(), task.getDocumentId());
        try {
            if (task.getType() == TaskType.INGEST || task.getType() == TaskType.REPARSE) {
                runSingle(task);
            } else {
                runRebuild(task);
            }
        } catch (TaskCanceledException ex) {
            log.info("Task #{} canceled", taskId);
        } catch (Exception ex) {
            log.error("Task #{} failed unexpectedly", taskId, ex);
            failTask(taskId, codeOf(ex), messageOf(ex), null);
        }
    }

    private void runSingle(KbIngestTask task) {
        Long taskId = task.getId();
        KbDocument document = documentRepository.findByIdAndDeletedAtIsNull(task.getDocumentId())
                .orElse(null);
        if (document == null) {
            failTask(taskId, ErrorCodes.DOCUMENT_NOT_FOUND, "文档不存在或已删除", null);
            return;
        }
        try {
            KbDocument processed = processDocument(taskId, task.getSeriesId(), document,
                    task.getConfigVersionId(), 5, 90);
            completeTask(taskId, null);
            writeLog(taskId, "DONE", "INFO", "文档处理完成，共生成切片 " + processed.getChunkCount() + " 个", null);
        } catch (TaskCanceledException ex) {
            resetDocumentAfterCancel(document.getId());
            throw ex;
        } catch (Exception ex) {
            markDocumentFailed(document.getId(), codeOf(ex), messageOf(ex));
            failTask(taskId, codeOf(ex), messageOf(ex), List.of(failedItem(document, codeOf(ex), messageOf(ex))));
        } finally {
            seriesService.refreshCounters(task.getSeriesId());
        }
    }

    private void runRebuild(KbIngestTask task) {
        Long taskId = task.getId();
        List<Long> documentIds = payloadDocumentIds(task);
        if (documentIds.isEmpty()) {
            failTask(taskId, ErrorCodes.VALIDATION, "重建任务没有待处理文档", null);
            return;
        }
        int total = documentIds.size();
        int done = 0;
        List<Map<String, Object>> failedItems = new ArrayList<>();
        try {
            for (Long documentId : documentIds) {
                if (cancelRequested(taskId)) {
                    throw new TaskCanceledException();
                }
                KbDocument document = documentRepository.findByIdAndDeletedAtIsNull(documentId).orElse(null);
                if (document == null) {
                    done++;
                    continue;
                }
                int base = 5 + (int) ((long) done * 90 / total);
                int span = Math.max(5, 90 / total);
                try {
                    processDocument(taskId, task.getSeriesId(), document,
                            task.getConfigVersionId(), base, span);
                } catch (TaskCanceledException ex) {
                    resetDocumentAfterCancel(documentId);
                    throw ex;
                } catch (Exception ex) {
                    markDocumentFailed(documentId, codeOf(ex), messageOf(ex));
                    failedItems.add(failedItem(document, codeOf(ex), messageOf(ex)));
                    writeLog(taskId, "DOCUMENT_FAILED", "ERROR",
                            "文档 [" + document.getName() + "] 处理失败: " + messageOf(ex), null);
                }
                done++;
            }
            if (failedItems.size() >= total) {
                failTask(taskId, ErrorCodes.PARSE_FAILED, "全部文档处理失败", failedItems);
            } else {
                completeTask(taskId, failedItems);
                writeLog(taskId, "DONE", "INFO",
                        "重建完成，成功 " + (total - failedItems.size()) + " 个，失败 " + failedItems.size() + " 个", null);
            }
        } finally {
            seriesService.refreshCounters(task.getSeriesId());
        }
    }

    private KbDocument processDocument(Long taskId, Long seriesId, KbDocument document, Long configVersionId,
                                       int baseProgress, int span) {
        if (cancelRequested(taskId)) {
            throw new TaskCanceledException();
        }
        KbConfigVersion version = configService.entityForTask(seriesId,
                configVersionId == null ? document.getConfigVersionId() : configVersionId);
        if (version == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.INVALID_CONFIG,
                    "知识库没有可用的解析配置");
        }
        ParseConfig parseConfig = configService.parse(version);
        ChunkConfig chunkConfig = configService.chunk(version);

        document.setStatus(DocStatus.PARSING);
        document.setConfigVersionId(version.getId());
        document.setErrorCode(null);
        document.setErrorMessage(null);
        document = documentRepository.save(document);
        updateProgress(taskId, baseProgress + Math.max(1, span * 5 / 100), "PARSE");
        writeLog(taskId, "PARSE", "INFO", "开始解析文档 [" + document.getName() + "]", null);
        long started = System.currentTimeMillis();
        ParseResult parsed = parserService.parse(storageService.resolve(document.getStorageKey()),
                document.getExt(), parseConfig);
        writeLog(taskId, "PARSE", "INFO",
                "解析完成：页数=" + parsed.pageCount() + "，字符数=" + parsed.charCount(), cost(started));

        if (cancelRequested(taskId)) {
            throw new TaskCanceledException();
        }
        document.setStatus(DocStatus.CHUNKING);
        document = documentRepository.save(document);
        updateProgress(taskId, baseProgress + Math.max(1, span * 40 / 100), "CHUNK");
        started = System.currentTimeMillis();
        List<ChunkingService.ChunkDraft> drafts = chunkingService.chunk(document.getName(), parsed, chunkConfig);
        if (drafts.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.EMPTY_DOCUMENT,
                    "文档没有生成任何切片");
        }
        writeLog(taskId, "CHUNK", "INFO", "切片完成：共 " + drafts.size() + " 个", cost(started));

        if (cancelRequested(taskId)) {
            throw new TaskCanceledException();
        }
        document.setStatus(DocStatus.INDEXING);
        document = documentRepository.save(document);
        updateProgress(taskId, baseProgress + Math.max(1, span * 65 / 100), "EMBED");
        started = System.currentTimeMillis();
        Map<Integer, String> vectors = new HashMap<>();
        for (ChunkingService.ChunkDraft draft : drafts) {
            if (draft.level() != ChunkLevel.PARENT) {
                vectors.put(draft.seq(), embeddingService.toJson(embeddingService.embed(draft.content())));
            }
        }
        writeLog(taskId, "EMBED", "INFO", "向量生成完成：" + vectors.size() + " 个", cost(started));

        if (cancelRequested(taskId)) {
            throw new TaskCanceledException();
        }
        updateProgress(taskId, baseProgress + Math.max(1, span * 85 / 100), "INDEX");
        started = System.currentTimeMillis();
        List<KbChunk> savedChunks = replaceChunks(document, drafts, vectors);
        document.setStatus(DocStatus.READY);
        document.setChunkCount(drafts.size());
        document.setPageCount(parsed.pageCount());
        document.setCharCount(parsed.charCount());
        document.setErrorCode(null);
        document.setErrorMessage(null);
        document = documentRepository.save(document);
        long searchIndexStarted = System.currentTimeMillis();
        if (!elasticsearchService.enabled()) {
            writeLog(taskId, "INDEX", "INFO", "Elasticsearch 未启用，使用本地 H2 索引", null);
        } else {
            ElasticsearchService.IndexResult result = elasticsearchService.replaceDocument(document, savedChunks);
            if (result.success()) {
                writeLog(taskId, "INDEX", "INFO",
                        "Elasticsearch 索引完成：" + result.count() + " 个切片", cost(searchIndexStarted));
            } else {
                writeLog(taskId, "INDEX", "WARN",
                        "Elasticsearch 索引失败：" + result.message() + "，检索将回退到本地索引", null);
            }
        }
        writeLog(taskId, "INDEX", "INFO", "索引写入完成，文档已就绪", cost(started));
        updateProgress(taskId, baseProgress + span, "DOCUMENT_DONE");
        return document;
    }

    private List<KbChunk> replaceChunks(KbDocument document, List<ChunkingService.ChunkDraft> drafts,
                                        Map<Integer, String> vectors) {
        return transactionTemplate.execute(status -> {
            chunkRepository.deleteByDocumentId(document.getId());
            List<KbChunk> saved = new ArrayList<>();
            Map<Integer, Long> parentIds = new HashMap<>();
            for (ChunkingService.ChunkDraft draft : drafts) {
                KbChunk chunk = new KbChunk();
                chunk.setSeriesId(document.getSeriesId());
                chunk.setDocumentId(document.getId());
                if (draft.parentIndex() != null) {
                    Long parentId = parentIds.get(draft.parentIndex());
                    if (parentId == null) {
                        throw new IllegalStateException("父切片顺序异常");
                    }
                    chunk.setParentChunkId(parentId);
                }
                chunk.setChunkLevel(draft.level());
                chunk.setSeq(draft.seq());
                chunk.setContent(draft.content());
                chunk.setContentHash(sha256(draft.content()));
                chunk.setTokenCount(draft.tokenCount());
                chunk.setCharStart(draft.charStart());
                chunk.setCharEnd(draft.charEnd());
                chunk.setPageNo(draft.pageNo());
                chunk.setTitlePath(draft.titlePath());
                chunk.setMetadata(draft.metadata());
                chunk.setVector(vectors.get(draft.seq()));
                chunk.setStatus("ACTIVE");
                chunkRepository.save(chunk);
                saved.add(chunk);
                parentIds.put(draft.seq(), chunk.getId());
            }
            return saved;
        });
    }

    private void resetDocumentAfterCancel(Long documentId) {
        documentRepository.findByIdAndDeletedAtIsNull(documentId).ifPresent(document -> {
            document.setStatus(DocStatus.PENDING);
            document.setErrorCode(null);
            document.setErrorMessage("任务已取消");
            documentRepository.save(document);
        });
    }

    private void markDocumentFailed(Long documentId, String code, String message) {
        documentRepository.findByIdAndDeletedAtIsNull(documentId).ifPresent(document -> {
            document.setStatus(DocStatus.FAILED);
            document.setErrorCode(code);
            document.setErrorMessage(truncate(message, 500));
            documentRepository.save(document);
        });
    }

    private boolean cancelRequested(Long taskId) {
        KbIngestTask current = taskRepository.findById(taskId).orElse(null);
        if (current == null) {
            return true;
        }
        if (current.getStatus() == TaskStatus.CANCELING) {
            current.setStatus(TaskStatus.CANCELED);
            current.setStage("CANCELED");
            current.setFinishedAt(Instant.now());
            taskRepository.save(current);
            writeLog(taskId, "CANCELED", "WARN", "任务已取消", null);
            return true;
        }
        return current.getStatus() == TaskStatus.CANCELED;
    }

    private void updateProgress(Long taskId, int progress, String stage) {
        taskRepository.findById(taskId).ifPresent(task -> {
            if (task.getStatus() == TaskStatus.RUNNING) {
                task.setProgress(Math.max(1, Math.min(99, progress)));
                task.setStage(stage);
                taskRepository.save(task);
            }
        });
    }

    private void completeTask(Long taskId, List<Map<String, Object>> failedItems) {
        taskRepository.findById(taskId).ifPresent(task -> {
            if (task.getStatus() == TaskStatus.CANCELING || task.getStatus() == TaskStatus.CANCELED) {
                task.setStatus(TaskStatus.CANCELED);
                task.setStage("CANCELED");
                task.setFinishedAt(Instant.now());
                taskRepository.save(task);
                return;
            }
            task.setStatus(TaskStatus.SUCCEEDED);
            task.setProgress(100);
            task.setStage("DONE");
            task.setFinishedAt(Instant.now());
            task.setErrorCode(null);
            task.setErrorMessage(null);
            if (failedItems != null && !failedItems.isEmpty()) {
                task.setFailedItems(writeJson(failedItems));
            }
            taskRepository.save(task);
        });
    }

    private void failTask(Long taskId, String code, String message, List<Map<String, Object>> failedItems) {
        taskRepository.findById(taskId).ifPresent(task -> {
            if (task.getStatus() == TaskStatus.CANCELING || task.getStatus() == TaskStatus.CANCELED) {
                task.setStatus(TaskStatus.CANCELED);
                task.setStage("CANCELED");
                task.setFinishedAt(Instant.now());
                taskRepository.save(task);
                return;
            }
            task.setStatus(TaskStatus.FAILED);
            task.setStage("FAILED");
            task.setErrorCode(code);
            task.setErrorMessage(truncate(message, 500));
            task.setFinishedAt(Instant.now());
            if (failedItems != null && !failedItems.isEmpty()) {
                task.setFailedItems(writeJson(failedItems));
            }
            taskRepository.save(task);
            writeLog(taskId, "FAILED", "ERROR", message, null);
        });
    }

    private List<Long> payloadDocumentIds(KbIngestTask task) {
        if (task.getPayload() == null || task.getPayload().isBlank()) {
            return task.getDocumentId() == null ? List.of() : List.of(task.getDocumentId());
        }
        try {
            var node = objectMapper.readTree(task.getPayload());
            List<Long> ids = new ArrayList<>();
            if (node.has("documentIds")) {
                for (var id : node.get("documentIds")) {
                    ids.add(id.asLong());
                }
            }
            return ids;
        } catch (Exception ex) {
            return task.getDocumentId() == null ? List.of() : List.of(task.getDocumentId());
        }
    }

    private Map<String, Object> failedItem(KbDocument document, String code, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("documentId", document.getId());
        item.put("name", document.getName());
        item.put("code", code);
        item.put("message", message);
        return item;
    }

    private void writeLog(Long taskId, String stage, String level, String message, Integer costMs) {
        try {
            KbTaskLog entry = new KbTaskLog();
            entry.setTaskId(taskId);
            entry.setStage(stage);
            entry.setLevel(level);
            entry.setMessage(truncate(message, 1000));
            entry.setCostMs(costMs);
            taskLogRepository.save(entry);
        } catch (Exception ex) {
            log.warn("Failed to write task log for #{}", taskId, ex);
        }
    }

    private int cost(long started) {
        return (int) (System.currentTimeMillis() - started);
    }

    private String codeOf(Exception ex) {
        return ex instanceof ApiException api ? api.getCode() : ErrorCodes.INTERNAL;
    }

    private String messageOf(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? "处理失败" : ex.getMessage();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return Integer.toHexString(content.hashCode());
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static class TaskCanceledException extends RuntimeException {
    }
}
