package com.kbconsole.document;

import com.kbconsole.common.ApiResponse;
import com.kbconsole.common.PageResult;
import com.kbconsole.tag.TagService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/kb/series/{seriesId}/documents")
@RequiredArgsConstructor
public class DocumentController {
    private final DocumentService documentService;

    public record TagsRequest(List<Long> tagIds) {
    }

    @GetMapping
    public ApiResponse<PageResult<DocumentService.DocumentView>> list(
            @PathVariable Long seriesId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long directoryId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(documentService.list(seriesId, keyword, directoryId, status, page, size));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DocumentService.UploadResult> upload(
            @PathVariable Long seriesId,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(required = false) Long directoryId,
            @RequestParam(defaultValue = "SKIP") String dedupeAction) {
        return ApiResponse.ok(documentService.upload(seriesId, files, directoryId, dedupeAction));
    }

    @GetMapping("/status-summary")
    public ApiResponse<Map<String, Object>> statusSummary(@PathVariable Long seriesId) {
        return ApiResponse.ok(documentService.statusSummary(seriesId));
    }

    @GetMapping("/{documentId}")
    public ApiResponse<DocumentService.DocumentView> detail(@PathVariable Long seriesId,
                                                            @PathVariable Long documentId) {
        return ApiResponse.ok(documentService.detail(seriesId, documentId));
    }

    @PatchMapping("/{documentId}")
    public ApiResponse<DocumentService.DocumentView> update(
            @PathVariable Long seriesId,
            @PathVariable Long documentId,
            @RequestBody DocumentService.MoveDocumentRequest request) {
        return ApiResponse.ok(documentService.update(seriesId, documentId, request));
    }

    @DeleteMapping("/{documentId}")
    public ApiResponse<Void> delete(@PathVariable Long seriesId, @PathVariable Long documentId) {
        documentService.delete(seriesId, documentId);
        return ApiResponse.ok();
    }

    @PostMapping("/{documentId}/reparse")
    public ApiResponse<Map<String, Object>> reparse(@PathVariable Long seriesId,
                                                    @PathVariable Long documentId) {
        return ApiResponse.ok(Map.of("taskId", documentService.reparse(seriesId, documentId)));
    }

    @PostMapping("/{documentId}/disable")
    public ApiResponse<Void> disable(@PathVariable Long seriesId, @PathVariable Long documentId) {
        documentService.disable(seriesId, documentId);
        return ApiResponse.ok();
    }

    @PostMapping("/{documentId}/enable")
    public ApiResponse<Map<String, Object>> enable(@PathVariable Long seriesId,
                                                   @PathVariable Long documentId) {
        Long taskId = documentService.enable(seriesId, documentId);
        return ApiResponse.ok(taskId == null ? Map.of() : Map.of("taskId", taskId));
    }

    @GetMapping("/{documentId}/chunks")
    public ApiResponse<PageResult<DocumentService.ChunkView>> chunks(
            @PathVariable Long seriesId,
            @PathVariable Long documentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(documentService.chunks(seriesId, documentId, page, size));
    }

    @GetMapping("/{documentId}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable Long seriesId,
                                                       @PathVariable Long documentId) {
        DocumentService.DownloadFile file = documentService.download(seriesId, documentId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.document().getName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(file.document().getMimeType()))
                .contentLength(file.document().getSizeBytes())
                .body(new FileSystemResource(file.path()));
    }

    @GetMapping("/{documentId}/tags")
    public ApiResponse<List<TagService.TagView>> tags(@PathVariable Long seriesId,
                                                      @PathVariable Long documentId) {
        return ApiResponse.ok(documentService.tags(seriesId, documentId));
    }

    @PutMapping("/{documentId}/tags")
    public ApiResponse<List<TagService.TagView>> setTags(@PathVariable Long seriesId,
                                                         @PathVariable Long documentId,
                                                         @RequestBody TagsRequest request) {
        return ApiResponse.ok(documentService.setTags(seriesId, documentId, request.tagIds()));
    }

    @PostMapping("/batch")
    public ApiResponse<DocumentService.BatchResult> batch(@PathVariable Long seriesId,
                                                          @RequestBody DocumentService.BatchRequest request) {
        return ApiResponse.ok(documentService.batch(seriesId, request));
    }
}
