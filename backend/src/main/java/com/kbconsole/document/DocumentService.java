package com.kbconsole.document;

import com.kbconsole.audit.AuditService;
import com.kbconsole.auth.AppUser;
import com.kbconsole.auth.AppUserRepository;
import com.kbconsole.common.*;
import com.kbconsole.directory.KbDirectory;
import com.kbconsole.directory.KbDirectoryRepository;
import com.kbconsole.ingest.KbChunk;
import com.kbconsole.ingest.KbChunkRepository;
import com.kbconsole.integration.ElasticsearchService;
import com.kbconsole.member.PermissionService;
import com.kbconsole.series.KbSeries;
import com.kbconsole.series.KbSeriesRepository;
import com.kbconsole.series.SeriesService;
import com.kbconsole.tag.KbDocumentTagRepository;
import com.kbconsole.tag.TagService;
import com.kbconsole.task.KbIngestTask;
import com.kbconsole.task.KbIngestTaskRepository;
import com.kbconsole.task.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {
    private static final Set<String> SUPPORTED_EXTS = Set.of("pdf", "docx", "xlsx", "xls", "md", "markdown");

    private final KbDocumentRepository documentRepository;
    private final KbChunkRepository chunkRepository;
    private final KbDocumentTagRepository documentTagRepository;
    private final KbDirectoryRepository directoryRepository;
    private final KbSeriesRepository seriesRepository;
    private final KbIngestTaskRepository taskRepository;
    private final AppUserRepository userRepository;
    private final PermissionService permissionService;
    private final SeriesService seriesService;
    private final TaskService taskService;
    private final TagService tagService;
    private final StorageService storageService;
    private final ElasticsearchService elasticsearchService;
    private final AuditService auditService;

    @Value("${kb.max-file-size:104857600}")
    private long maxFileSize;

    @Value("${kb.max-batch-size:50}")
    private int maxBatchSize;

    public record DocumentView(Long id, Long seriesId, Long directoryId, String directoryName, String name,
                               String ext, String mimeType, long sizeBytes, Integer pageCount, Long charCount,
                               String status, String errorCode, String errorMessage, int chunkCount,
                               Long configVersionId, Long uploadedBy, String uploadedByName,
                               List<TagService.TagView> tags, Long latestTaskId, String latestTaskStatus,
                               Instant createdAt, Instant updatedAt) {
    }

    public record FailedItem(String name, String code, String message) {
    }

    public record UploadResult(List<DocumentView> items, List<FailedItem> failedItems) {
    }

    public record MoveDocumentRequest(Long directoryId, String name) {
    }

    public record BatchRequest(String action, List<Long> documentIds, Long directoryId, List<Long> tagIds) {
    }

    public record BatchResult(int successCount, int failedCount, List<FailedItem> failedItems) {
    }

    public record ChunkView(Long id, int seq, String level, String content, int tokenCount, Integer pageNo,
                            String titlePath, Long parentChunkId) {
    }

    public record DownloadFile(KbDocument document, Path path) {
    }

    @Transactional
    public UploadResult upload(Long seriesId, List<MultipartFile> files, Long directoryId, String dedupeAction) {
        CurrentUser user = UserContext.require();
        permissionService.requireMaintain(seriesId);
        KbSeries series = requireSeries(seriesId);
        ensureActive(series, "知识库已停用，暂不能上传文档");
        if (files == null || files.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "请选择要上传的文件");
        }
        if (files.size() > maxBatchSize) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION,
                    "单次最多上传 " + maxBatchSize + " 个文件");
        }
        KbDirectory directory = resolveDirectory(seriesId, directoryId);
        String action = dedupeAction == null || dedupeAction.isBlank()
                ? "SKIP" : dedupeAction.toUpperCase(Locale.ROOT);
        if (!"SKIP".equals(action) && !"OVERWRITE".equals(action)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "重复文件处理方式不合法");
        }
        AppUser uploader = userRepository.findById(user.id()).orElse(null);
        List<DocumentView> items = new ArrayList<>();
        List<FailedItem> failed = new ArrayList<>();
        for (MultipartFile file : files) {
            String displayName = sanitizeName(file.getOriginalFilename());
            try {
                DocumentView view = uploadOne(series, directory, file, displayName, action, user, uploader);
                items.add(view);
            } catch (ApiException ex) {
                failed.add(new FailedItem(displayName, ex.getCode(), ex.getMessage()));
            } catch (Exception ex) {
                log.warn("Upload failed for {}", displayName, ex);
                failed.add(new FailedItem(displayName, ErrorCodes.INTERNAL, "文件上传失败"));
            }
        }
        seriesService.refreshCounters(seriesId);
        auditService.record(seriesId, "DOCUMENT_UPLOAD", "DOCUMENT", null,
                Map.of("success", items.size(), "failed", failed.size()));
        return new UploadResult(items, failed);
    }

    private DocumentView uploadOne(KbSeries series, KbDirectory directory, MultipartFile file,
                                   String displayName, String dedupeAction, CurrentUser user, AppUser uploader) {
        if (file.isEmpty() || file.getSize() == 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.EMPTY_DOCUMENT, "文件为空");
        }
        if (file.getSize() > maxFileSize) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.FILE_TOO_LARGE, "文件超过大小限制");
        }
        String ext = extensionOf(displayName);
        if (!SUPPORTED_EXTS.contains(ext)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.UNSUPPORTED_TYPE,
                    "仅支持 PDF、Word(docx)、Excel(xlsx/xls)、Markdown(md) 文档");
        }
        StorageService.StoredFile stored = storageService.store(series.getId(), file, ext);
        String sha = storageService.sha256(stored.path());
        KbDocument existing = documentRepository
                .findBySeriesIdAndSha256AndDeletedAtIsNull(series.getId(), sha).orElse(null);
        if (existing != null) {
            if ("SKIP".equals(dedupeAction)) {
                storageService.deleteQuietly(stored.key());
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DUPLICATE_DOCUMENT,
                        "相同内容的文档已存在：" + existing.getName());
            }
            softDelete(existing);
        }
        KbDocument document = new KbDocument();
        document.setSeriesId(series.getId());
        document.setDirectoryId(directory.getId());
        document.setName(displayName);
        document.setExt(ext);
        document.setMimeType(mimeOf(ext));
        document.setSizeBytes(file.getSize());
        document.setSha256(sha);
        document.setStorageKey(stored.key());
        document.setStatus(DocStatus.PENDING);
        document.setUploadedBy(user.id());
        document.setConfigVersionId(series.getActiveConfigVersionId());
        documentRepository.save(document);
        Long taskId = taskService.createIngest(series.getId(), document.getId(), TaskType.INGEST,
                document.getConfigVersionId(), user);
        log.info("Document #{} [{}] uploaded to series #{} by {}, task #{}",
                document.getId(), document.getName(), series.getId(), user.account(), taskId);
        return new DocumentView(document.getId(), series.getId(), directory.getId(), directory.getName(),
                document.getName(), document.getExt(), document.getMimeType(), document.getSizeBytes(),
                null, null, document.getStatus().name(), null, null, 0, document.getConfigVersionId(),
                user.id(), uploader == null ? user.name() : uploader.getName(), List.of(),
                taskId, TaskStatus.QUEUED.name(), document.getCreatedAt(), document.getUpdatedAt());
    }

    private void softDelete(KbDocument document) {
        document.setStatus(DocStatus.DELETED);
        document.setDeletedAt(Instant.now());
        documentRepository.save(document);
        chunkRepository.deleteByDocumentId(document.getId());
        documentTagRepository.deleteByDocumentId(document.getId());
        TransactionHooks.afterCommit(() -> elasticsearchService.deleteByDocument(document.getId()));
    }

    public PageResult<DocumentView> list(Long seriesId, String keyword, Long directoryId, String status,
                                         int page, int size) {
        permissionService.requireRead(seriesId);
        List<KbDocument> documents = documentRepository
                .findBySeriesIdAndDeletedAtIsNullOrderByUpdatedAtDesc(seriesId);
        String keywordLower = keyword == null ? null : keyword.trim().toLowerCase(Locale.ROOT);
        Set<Long> directoryIds = null;
        if (directoryId != null) {
            KbDirectory directory = directoryRepository.findByIdAndSeriesId(directoryId, seriesId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            ErrorCodes.DIRECTORY_NOT_FOUND, "目录不存在"));
            if (!directory.isRoot()) {
                directoryIds = directoryRepository.findBySeriesIdAndPathStartingWith(seriesId, directory.getPath())
                        .stream().map(KbDirectory::getId).collect(Collectors.toSet());
            }
        }
        Set<Long> directoryFilter = directoryIds;
        List<KbDocument> filtered = documents.stream()
                .filter(d -> status == null || status.isBlank() || d.getStatus().name().equalsIgnoreCase(status))
                .filter(d -> keywordLower == null || keywordLower.isBlank()
                        || d.getName().toLowerCase(Locale.ROOT).contains(keywordLower))
                .filter(d -> directoryFilter == null || directoryFilter.contains(d.getDirectoryId()))
                .toList();
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(1, page);
        int from = Math.min((safePage - 1) * safeSize, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());
        Map<Long, String> directoryNames = directoryRepository.findBySeriesIdOrderBySortOrderAscIdAsc(seriesId)
                .stream().collect(Collectors.toMap(KbDirectory::getId, KbDirectory::getName));
        Set<Long> uploaderIds = filtered.stream().map(KbDocument::getUploadedBy).collect(Collectors.toSet());
        Map<Long, AppUser> uploaders = userRepository.findAllById(uploaderIds).stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity()));
        List<DocumentView> items = filtered.subList(from, to).stream()
                .map(d -> toView(d, directoryNames.get(d.getDirectoryId()),
                        uploaders.get(d.getUploadedBy())))
                .toList();
        return new PageResult<>(items, filtered.size(), safePage, safeSize);
    }

    public DocumentView detail(Long seriesId, Long documentId) {
        permissionService.requireRead(seriesId);
        KbDocument document = requireDocument(seriesId, documentId);
        KbDirectory directory = directoryRepository.findByIdAndSeriesId(document.getDirectoryId(), seriesId)
                .orElse(null);
        AppUser uploader = userRepository.findById(document.getUploadedBy()).orElse(null);
        return toView(document, directory == null ? null : directory.getName(), uploader);
    }

    @Transactional
    public DocumentView update(Long seriesId, Long documentId, MoveDocumentRequest request) {
        permissionService.requireMaintain(seriesId);
        KbSeries series = requireSeries(seriesId);
        ensureActive(series, "知识库已停用，暂不能修改文档");
        KbDocument document = requireDocument(seriesId, documentId);
        if (request.directoryId() != null) {
            KbDirectory target = resolveDirectory(seriesId, request.directoryId());
            document.setDirectoryId(target.getId());
        }
        if (request.name() != null) {
            String name = request.name().trim();
            if (name.isEmpty() || name.length() > 255) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "文档名称需为 1-255 个字符");
            }
            document.setName(name);
        }
        documentRepository.save(document);
        auditService.record(seriesId, "DOCUMENT_UPDATE", "DOCUMENT", documentId, Map.of());
        KbDirectory directory = directoryRepository.findByIdAndSeriesId(document.getDirectoryId(), seriesId)
                .orElse(null);
        AppUser uploader = userRepository.findById(document.getUploadedBy()).orElse(null);
        return toView(document, directory == null ? null : directory.getName(), uploader);
    }

    @Transactional
    public void delete(Long seriesId, Long documentId) {
        permissionService.requireMaintain(seriesId);
        KbDocument document = requireDocument(seriesId, documentId);
        softDelete(document);
        seriesService.refreshCounters(seriesId);
        auditService.record(seriesId, "DOCUMENT_DELETE", "DOCUMENT", documentId,
                Map.of("name", document.getName()));
        log.info("Document #{} [{}] deleted from series #{}", documentId, document.getName(), seriesId);
    }

    @Transactional
    public Long reparse(Long seriesId, Long documentId) {
        CurrentUser user = UserContext.require();
        permissionService.requireMaintain(seriesId);
        KbSeries series = requireSeries(seriesId);
        ensureActive(series, "知识库已停用，暂不能重新解析");
        KbDocument document = requireDocument(seriesId, documentId);
        if (document.getStatus() == DocStatus.DISABLED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.TASK_CONFLICT, "请先启用文档后再重新解析");
        }
        boolean running = taskRepository.findByDocumentIdOrderByCreatedAtDesc(documentId).stream()
                .anyMatch(t -> t.getStatus() == TaskStatus.QUEUED || t.getStatus() == TaskStatus.RUNNING
                        || t.getStatus() == TaskStatus.CANCELING);
        if (running) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.TASK_CONFLICT, "文档已有进行中的任务");
        }
        document.setStatus(DocStatus.PENDING);
        document.setErrorCode(null);
        document.setErrorMessage(null);
        document.setConfigVersionId(series.getActiveConfigVersionId());
        documentRepository.save(document);
        Long taskId = taskService.createIngest(seriesId, documentId, TaskType.REPARSE,
                series.getActiveConfigVersionId(), user);
        seriesService.refreshCounters(seriesId);
        auditService.record(seriesId, "DOCUMENT_REPARSE", "DOCUMENT", documentId, Map.of("taskId", taskId));
        return taskId;
    }

    @Transactional
    public void disable(Long seriesId, Long documentId) {
        permissionService.requireMaintain(seriesId);
        KbSeries series = requireSeries(seriesId);
        ensureActive(series, "知识库已停用，暂不能修改文档");
        KbDocument document = requireDocument(seriesId, documentId);
        document.setStatus(DocStatus.DISABLED);
        documentRepository.save(document);
        for (KbChunk chunk : chunkRepository.findByDocumentIdOrderBySeqAsc(documentId)) {
            chunk.setStatus("DISABLED");
            chunkRepository.save(chunk);
        }
        seriesService.refreshCounters(seriesId);
        auditService.record(seriesId, "DOCUMENT_DISABLE", "DOCUMENT", documentId, Map.of());
    }

    @Transactional
    public Long enable(Long seriesId, Long documentId) {
        CurrentUser user = UserContext.require();
        permissionService.requireMaintain(seriesId);
        KbSeries series = requireSeries(seriesId);
        ensureActive(series, "知识库已停用，暂不能修改文档");
        KbDocument document = requireDocument(seriesId, documentId);
        if (document.getStatus() != DocStatus.DISABLED) {
            return null;
        }
        List<KbChunk> chunks = chunkRepository.findByDocumentIdOrderBySeqAsc(documentId);
        if (chunks.isEmpty()) {
            document.setStatus(DocStatus.PENDING);
            document.setConfigVersionId(series.getActiveConfigVersionId());
            documentRepository.save(document);
            Long taskId = taskService.createIngest(seriesId, documentId, TaskType.INGEST,
                    series.getActiveConfigVersionId(), user);
            seriesService.refreshCounters(seriesId);
            auditService.record(seriesId, "DOCUMENT_ENABLE", "DOCUMENT", documentId, Map.of("taskId", taskId));
            return taskId;
        }
        for (KbChunk chunk : chunks) {
            chunk.setStatus("ACTIVE");
            chunkRepository.save(chunk);
        }
        document.setStatus(DocStatus.READY);
        document.setErrorCode(null);
        document.setErrorMessage(null);
        documentRepository.save(document);
        seriesService.refreshCounters(seriesId);
        auditService.record(seriesId, "DOCUMENT_ENABLE", "DOCUMENT", documentId, Map.of());
        return null;
    }

    @Transactional
    public BatchResult batch(Long seriesId, BatchRequest request) {
        CurrentUser user = UserContext.require();
        permissionService.requireMaintain(seriesId);
        if (request.documentIds() == null || request.documentIds().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "请选择文档");
        }
        if (request.documentIds().size() > maxBatchSize) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION,
                    "单次最多操作 " + maxBatchSize + " 个文档");
        }
        String action = request.action() == null ? "" : request.action().toUpperCase(Locale.ROOT);
        int success = 0;
        List<FailedItem> failed = new ArrayList<>();
        for (Long documentId : request.documentIds().stream().distinct().toList()) {
            String name = documentRepository.findById(documentId).map(KbDocument::getName).orElse("文档#" + documentId);
            try {
                switch (action) {
                    case "DELETE" -> delete(seriesId, documentId);
                    case "REPARSE" -> reparse(seriesId, documentId);
                    case "MOVE" -> update(seriesId, documentId,
                            new MoveDocumentRequest(request.directoryId(), null));
                    case "DISABLE" -> disable(seriesId, documentId);
                    case "ENABLE" -> enable(seriesId, documentId);
                    case "SET_TAGS" -> {
                        permissionService.requireMaintain(seriesId);
                        requireDocument(seriesId, documentId);
                        tagService.setDocumentTags(documentId,
                                new TagService.SetTagsRequest(request.tagIds() == null
                                        ? List.of() : request.tagIds()));
                    }
                    default -> throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION,
                            "批量操作类型不合法");
                }
                success++;
            } catch (ApiException ex) {
                failed.add(new FailedItem(name, ex.getCode(), ex.getMessage()));
            } catch (Exception ex) {
                log.warn("Batch action {} failed for document #{}", action, documentId, ex);
                failed.add(new FailedItem(name, ErrorCodes.INTERNAL, "操作失败"));
            }
        }
        seriesService.refreshCounters(seriesId);
        auditService.record(seriesId, "DOCUMENT_BATCH", "DOCUMENT", null,
                Map.of("action", action, "success", success, "failed", failed.size()));
        return new BatchResult(success, failed.size(), failed);
    }

    public PageResult<ChunkView> chunks(Long seriesId, Long documentId, int page, int size) {
        permissionService.requireRead(seriesId);
        requireDocument(seriesId, documentId);
        List<KbChunk> chunks = chunkRepository.findByDocumentIdOrderBySeqAsc(documentId);
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(1, page);
        int from = Math.min((safePage - 1) * safeSize, chunks.size());
        int to = Math.min(from + safeSize, chunks.size());
        List<ChunkView> items = chunks.subList(from, to).stream()
                .map(c -> new ChunkView(c.getId(), c.getSeq(), c.getChunkLevel().name(), c.getContent(),
                        c.getTokenCount(), c.getPageNo(), c.getTitlePath(), c.getParentChunkId()))
                .toList();
        return new PageResult<>(items, chunks.size(), safePage, safeSize);
    }

    public DownloadFile download(Long seriesId, Long documentId) {
        permissionService.requireRead(seriesId);
        KbDocument document = requireDocument(seriesId, documentId);
        Path path = storageService.resolve(document.getStorageKey());
        if (!Files.exists(path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.DOCUMENT_NOT_FOUND, "原始文件不存在");
        }
        return new DownloadFile(document, path);
    }

    public List<TagService.TagView> tags(Long seriesId, Long documentId) {
        permissionService.requireRead(seriesId);
        requireDocument(seriesId, documentId);
        return tagService.tagsOfDocument(documentId);
    }

    @Transactional
    public List<TagService.TagView> setTags(Long seriesId, Long documentId, List<Long> tagIds) {
        permissionService.requireMaintain(seriesId);
        requireDocument(seriesId, documentId);
        return tagService.setDocumentTags(documentId, new TagService.SetTagsRequest(tagIds));
    }

    public Map<String, Object> statusSummary(Long seriesId) {
        permissionService.requireRead(seriesId);
        List<KbDocument> documents = documentRepository.findBySeriesIdAndDeletedAtIsNullOrderByUpdatedAtDesc(seriesId);
        Map<String, Object> summary = new LinkedHashMap<>();
        for (DocStatus status : DocStatus.values()) {
            summary.put(status.name(), documents.stream().filter(d -> d.getStatus() == status).count());
        }
        summary.put("total", documents.size());
        return summary;
    }

    private DocumentView toView(KbDocument document, String directoryName, AppUser uploader) {
        KbIngestTask latest = taskRepository.findByDocumentIdOrderByCreatedAtDesc(document.getId())
                .stream().findFirst().orElse(null);
        return new DocumentView(document.getId(), document.getSeriesId(), document.getDirectoryId(),
                directoryName, document.getName(), document.getExt(), document.getMimeType(),
                document.getSizeBytes(), document.getPageCount(), document.getCharCount(),
                document.getStatus().name(), document.getErrorCode(), document.getErrorMessage(),
                document.getChunkCount(), document.getConfigVersionId(), document.getUploadedBy(),
                uploader == null ? null : uploader.getName(), tagService.tagsOfDocument(document.getId()),
                latest == null ? null : latest.getId(),
                latest == null ? null : latest.getStatus().name(),
                document.getCreatedAt(), document.getUpdatedAt());
    }

    private KbDocument requireDocument(Long seriesId, Long documentId) {
        return documentRepository.findByIdAndDeletedAtIsNull(documentId)
                .filter(d -> Objects.equals(d.getSeriesId(), seriesId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCodes.DOCUMENT_NOT_FOUND, "文档不存在"));
    }

    private KbSeries requireSeries(Long seriesId) {
        return seriesRepository.findByIdAndDeletedAtIsNull(seriesId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.SERIES_NOT_FOUND, "知识库不存在"));
    }

    private KbDirectory resolveDirectory(Long seriesId, Long directoryId) {
        if (directoryId == null) {
            return directoryRepository.findFirstBySeriesIdAndRootTrue(seriesId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            ErrorCodes.DIRECTORY_NOT_FOUND, "根目录不存在"));
        }
        return directoryRepository.findByIdAndSeriesId(directoryId, seriesId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCodes.DIRECTORY_NOT_FOUND, "目录不存在"));
    }

    private void ensureActive(KbSeries series, String message) {
        if (series.getStatus() != SeriesStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.SERIES_LOCKED, message);
        }
    }

    private String sanitizeName(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        int index = name.lastIndexOf('/');
        if (index >= 0) {
            name = name.substring(index + 1);
        }
        name = name.trim();
        return name.isEmpty() ? "未命名文档" : name;
    }

    private String extensionOf(String name) {
        int index = name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private String mimeOf(String ext) {
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "xls" -> "application/vnd.ms-excel";
            case "md", "markdown" -> "text/markdown";
            default -> "application/octet-stream";
        };
    }
}
