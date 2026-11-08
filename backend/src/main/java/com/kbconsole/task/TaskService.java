package com.kbconsole.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbconsole.audit.AuditService;
import com.kbconsole.auth.AppUser;
import com.kbconsole.auth.AppUserRepository;
import com.kbconsole.common.*;
import com.kbconsole.config.ConfigService;
import com.kbconsole.document.KbDocument;
import com.kbconsole.document.KbDocumentRepository;
import com.kbconsole.ingest.IngestProcessor;
import com.kbconsole.member.PermissionService;
import com.kbconsole.series.KbSeries;
import com.kbconsole.series.KbSeriesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {
    private final KbIngestTaskRepository taskRepository;
    private final KbTaskLogRepository logRepository;
    private final KbDocumentRepository documentRepository;
    private final KbSeriesRepository seriesRepository;
    private final AppUserRepository userRepository;
    private final ConfigService configService;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final IngestProcessor ingestProcessor;
    private final ObjectMapper objectMapper;

    public record TaskView(Long id, Long seriesId, Long documentId, String documentName, String type,
                           String status, int progress, String stage, int retryCount, Long retryOfTaskId,
                           Long configVersionId, String errorCode, String errorMessage, Object failedItems,
                           Long operatorId, String operatorName, Instant createdAt, Instant startedAt,
                           Instant finishedAt) {
    }

    public record TaskLogView(Long id, String stage, String level, String message, Integer costMs, Instant createdAt) {
    }

    public record TaskDetail(TaskView task, List<TaskLogView> logs) {
    }

    public Long createIngest(Long seriesId, Long documentId, TaskType type, Long configVersionId,
                             CurrentUser user) {
        KbIngestTask task = newTask(seriesId, documentId, type, configVersionId, user, null);
        taskRepository.save(task);
        log(task.getId(), "QUEUED", "INFO", "任务已创建", null);
        schedule(task.getId());
        return task.getId();
    }

    public Long createRebuild(Long seriesId, String scope, List<Long> documentIds, Long configVersionId,
                              CurrentUser user) {
        permissionService.requireAdmin(seriesId);
        KbSeries series = seriesRepository.findByIdAndDeletedAtIsNull(seriesId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.SERIES_NOT_FOUND, "知识库不存在"));
        if (series.getStatus() == SeriesStatus.DELETING || series.getStatus() == SeriesStatus.DELETED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.SERIES_DELETING, "知识库正在删除中");
        }
        boolean failedOnly = "FAILED".equalsIgnoreCase(scope);
        Set<Long> requested = documentIds == null ? Set.of() : Set.copyOf(documentIds);
        List<KbDocument> documents = documentRepository
                .findBySeriesIdAndDeletedAtIsNullOrderByUpdatedAtDesc(seriesId).stream()
                .filter(d -> d.getStatus() == DocStatus.READY || d.getStatus() == DocStatus.FAILED)
                .filter(d -> !failedOnly || d.getStatus() == DocStatus.FAILED)
                .filter(d -> requested.isEmpty() || requested.contains(d.getId()))
                .toList();
        if (documents.isEmpty()) {
            return null;
        }
        List<Long> ids = new ArrayList<>();
        for (KbDocument document : documents) {
            document.setStatus(DocStatus.PENDING);
            document.setConfigVersionId(configVersionId);
            document.setErrorCode(null);
            document.setErrorMessage(null);
            documentRepository.save(document);
            ids.add(document.getId());
        }
        KbIngestTask task = newTask(seriesId, null, failedOnly ? TaskType.REBUILD : TaskType.FULL_REBUILD,
                configVersionId, user, null);
        task.setPayload(writePayload(ids, scope));
        taskRepository.save(task);
        log(task.getId(), "QUEUED", "INFO", "重建任务已创建，待处理文档 " + ids.size() + " 个", null);
        auditService.record(seriesId, "TASK_REBUILD", "TASK", task.getId(),
                Map.of("scope", scope, "documents", ids.size()));
        schedule(task.getId());
        return task.getId();
    }

    public PageResult<TaskView> list(Long seriesId, String status, String type, int page, int size) {
        permissionService.requireRead(seriesId);
        List<KbIngestTask> tasks = taskRepository.findBySeriesIdOrderByCreatedAtDesc(seriesId).stream()
                .filter(t -> status == null || status.isBlank() || t.getStatus().name().equalsIgnoreCase(status))
                .filter(t -> type == null || type.isBlank() || t.getType().name().equalsIgnoreCase(type))
                .toList();
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(1, page);
        int from = Math.min((safePage - 1) * safeSize, tasks.size());
        int to = Math.min(from + safeSize, tasks.size());
        List<TaskView> items = toViews(tasks.subList(from, to));
        return new PageResult<>(items, tasks.size(), safePage, safeSize);
    }

    public TaskDetail detail(Long seriesId, Long taskId) {
        permissionService.requireRead(seriesId);
        KbIngestTask task = requireTask(seriesId, taskId);
        List<TaskLogView> logs = logRepository.findByTaskIdOrderByCreatedAtAsc(taskId).stream()
                .map(l -> new TaskLogView(l.getId(), l.getStage(), l.getLevel(), l.getMessage(),
                        l.getCostMs(), l.getCreatedAt()))
                .toList();
        return new TaskDetail(toViews(List.of(task)).get(0), logs);
    }

    public List<TaskLogView> logs(Long seriesId, Long taskId) {
        permissionService.requireRead(seriesId);
        requireTask(seriesId, taskId);
        return logRepository.findByTaskIdOrderByCreatedAtAsc(taskId).stream()
                .map(l -> new TaskLogView(l.getId(), l.getStage(), l.getLevel(), l.getMessage(),
                        l.getCostMs(), l.getCreatedAt()))
                .toList();
    }

    @Transactional
    public TaskView retry(Long seriesId, Long taskId) {
        CurrentUser user = UserContext.require();
        permissionService.requireMaintain(seriesId);
        KbIngestTask task = requireTask(seriesId, taskId);
        if (task.getStatus() != TaskStatus.FAILED && task.getStatus() != TaskStatus.CANCELED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.TASK_CONFLICT, "只有失败或已取消的任务可以重试");
        }
        if (task.getType() == TaskType.INGEST || task.getType() == TaskType.REPARSE) {
            KbDocument document = task.getDocumentId() == null ? null
                    : documentRepository.findByIdAndDeletedAtIsNull(task.getDocumentId()).orElse(null);
            if (document == null) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DOCUMENT_NOT_FOUND, "文档已删除，无法重试");
            }
            document.setStatus(DocStatus.PENDING);
            document.setErrorCode(null);
            document.setErrorMessage(null);
            documentRepository.save(document);
        } else {
            for (Long documentId : payloadDocumentIds(task)) {
                documentRepository.findByIdAndDeletedAtIsNull(documentId).ifPresent(document -> {
                    document.setStatus(DocStatus.PENDING);
                    document.setErrorCode(null);
                    document.setErrorMessage(null);
                    documentRepository.save(document);
                });
            }
        }
        KbIngestTask retry = newTask(seriesId, task.getDocumentId(), task.getType(),
                task.getConfigVersionId(), user, task);
        retry.setPayload(task.getPayload());
        taskRepository.save(retry);
        log(retry.getId(), "QUEUED", "INFO", "重试任务已创建，来源任务 #" + task.getId(), null);
        schedule(retry.getId());
        auditService.record(seriesId, "TASK_RETRY", "TASK", retry.getId(), Map.of("sourceTaskId", task.getId()));
        return toViews(List.of(retry)).get(0);
    }

    @Transactional
    public void cancel(Long seriesId, Long taskId) {
        permissionService.requireMaintain(seriesId);
        KbIngestTask task = requireTask(seriesId, taskId);
        if (task.getStatus() == TaskStatus.QUEUED) {
            task.setStatus(TaskStatus.CANCELED);
            task.setStage("CANCELED");
            task.setFinishedAt(Instant.now());
            taskRepository.save(task);
            log(taskId, "CANCELED", "WARN", "任务在排队阶段被取消", null);
            return;
        }
        if (task.getStatus() == TaskStatus.RUNNING) {
            task.setStatus(TaskStatus.CANCELING);
            taskRepository.save(task);
            log(taskId, "CANCELING", "WARN", "收到取消请求，将在当前阶段结束后停止", null);
            return;
        }
        if (task.getStatus() == TaskStatus.CANCELING) {
            return;
        }
        throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.TASK_CONFLICT, "任务已结束，无法取消");
    }

    public void log(Long taskId, String stage, String level, String message, Integer costMs) {
        KbTaskLog entry = new KbTaskLog();
        entry.setTaskId(taskId);
        entry.setStage(stage);
        entry.setLevel(level);
        entry.setMessage(message == null ? "" : truncate(message, 1000));
        entry.setCostMs(costMs);
        logRepository.save(entry);
    }

    private KbIngestTask newTask(Long seriesId, Long documentId, TaskType type, Long configVersionId,
                                 CurrentUser user, KbIngestTask retryOf) {
        KbIngestTask task = new KbIngestTask();
        task.setSeriesId(seriesId);
        task.setDocumentId(documentId);
        task.setType(type);
        task.setStatus(TaskStatus.QUEUED);
        task.setProgress(0);
        task.setStage("QUEUED");
        task.setConfigVersionId(configVersionId);
        task.setIdempotencyKey(UUID.randomUUID().toString());
        task.setOperatorId(user.id());
        if (retryOf != null) {
            task.setRetryOfTaskId(retryOf.getId());
            task.setRetryCount(retryOf.getRetryCount() + 1);
        }
        return task;
    }

    private void schedule(Long taskId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    log.info("Submitting task #{} after commit", taskId);
                    ingestProcessor.runAsync(taskId);
                }
            });
        } else {
            log.info("Submitting task #{} immediately", taskId);
            ingestProcessor.runAsync(taskId);
        }
    }

    private KbIngestTask requireTask(Long seriesId, Long taskId) {
        return taskRepository.findByIdAndSeriesId(taskId, seriesId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.TASK_NOT_FOUND, "任务不存在"));
    }

    private List<TaskView> toViews(List<KbIngestTask> tasks) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        Set<Long> documentIds = tasks.stream().map(KbIngestTask::getDocumentId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, KbDocument> documents = documentRepository.findAllById(documentIds).stream()
                .collect(Collectors.toMap(KbDocument::getId, Function.identity()));
        Set<Long> operatorIds = tasks.stream().map(KbIngestTask::getOperatorId).collect(Collectors.toSet());
        Map<Long, AppUser> operators = userRepository.findAllById(operatorIds).stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity()));
        List<TaskView> result = new ArrayList<>();
        for (KbIngestTask task : tasks) {
            KbDocument document = task.getDocumentId() == null ? null : documents.get(task.getDocumentId());
            AppUser operator = operators.get(task.getOperatorId());
            result.add(new TaskView(task.getId(), task.getSeriesId(), task.getDocumentId(),
                    document == null ? null : document.getName(), task.getType().name(), task.getStatus().name(),
                    task.getProgress(), task.getStage(), task.getRetryCount(), task.getRetryOfTaskId(),
                    task.getConfigVersionId(), task.getErrorCode(), task.getErrorMessage(),
                    parseJson(task.getFailedItems()), task.getOperatorId(),
                    operator == null ? null : operator.getName(), task.getCreatedAt(), task.getStartedAt(),
                    task.getFinishedAt()));
        }
        return result;
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

    private String writePayload(List<Long> documentIds, String scope) {
        try {
            return objectMapper.writeValueAsString(Map.of("documentIds", documentIds,
                    "scope", scope == null ? "FULL" : scope.toUpperCase(Locale.ROOT)));
        } catch (Exception ex) {
            return "{\"documentIds\":[]}";
        }
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
