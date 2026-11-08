package com.kbconsole.tag;

import com.kbconsole.audit.AuditService;
import com.kbconsole.common.*;
import com.kbconsole.document.KbDocument;
import com.kbconsole.document.KbDocumentRepository;
import com.kbconsole.member.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TagService {
    private final KbTagRepository tagRepository;
    private final KbDocumentTagRepository documentTagRepository;
    private final KbDocumentRepository documentRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;

    public record TagView(Long id, String name, String color, long docCount) {
    }

    public record CreateTagRequest(String name, String color) {
    }

    public record RenameTagRequest(String name, String color) {
    }

    public record SetTagsRequest(List<Long> tagIds) {
    }

    public List<TagView> list(Long seriesId) {
        permissionService.requireRead(seriesId);
        List<TagView> result = new ArrayList<>();
        for (KbTag tag : tagRepository.findBySeriesIdOrderByIdAsc(seriesId)) {
            result.add(new TagView(tag.getId(), tag.getName(), tag.getColor(),
                    documentTagRepository.findByTagId(tag.getId()).size()));
        }
        return result;
    }

    @Transactional
    public TagView create(Long seriesId, CreateTagRequest request) {
        CurrentUser user = UserContext.require();
        permissionService.requireMaintain(seriesId);
        String name = validateName(request.name());
        if (tagRepository.existsBySeriesIdAndName(seriesId, name)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.DUPLICATE_TAG, "已存在同名标签");
        }
        KbTag tag = new KbTag();
        tag.setSeriesId(seriesId);
        tag.setName(name);
        tag.setColor(normalizeColor(request.color()));
        tag.setCreatedBy(user.id());
        tagRepository.save(tag);
        auditService.record(seriesId, "TAG_CREATE", "TAG", tag.getId(), Map.of("name", name));
        return new TagView(tag.getId(), tag.getName(), tag.getColor(), 0);
    }

    @Transactional
    public TagView rename(Long seriesId, Long tagId, RenameTagRequest request) {
        permissionService.requireMaintain(seriesId);
        KbTag tag = tagRepository.findByIdAndSeriesId(tagId, seriesId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.VALIDATION, "标签不存在"));
        String name = validateName(request.name());
        List<KbTag> tags = tagRepository.findBySeriesIdOrderByIdAsc(seriesId);
        if (tags.stream().anyMatch(t -> !t.getId().equals(tagId) && t.getName().equalsIgnoreCase(name))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.DUPLICATE_TAG, "已存在同名标签");
        }
        tag.setName(name);
        tag.setColor(normalizeColor(request.color()));
        tagRepository.save(tag);
        auditService.record(seriesId, "TAG_UPDATE", "TAG", tagId, Map.of("name", name));
        return new TagView(tag.getId(), tag.getName(), tag.getColor(),
                documentTagRepository.findByTagId(tag.getId()).size());
    }

    @Transactional
    public void delete(Long seriesId, Long tagId) {
        permissionService.requireMaintain(seriesId);
        KbTag tag = tagRepository.findByIdAndSeriesId(tagId, seriesId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.VALIDATION, "标签不存在"));
        documentTagRepository.deleteByTagId(tagId);
        tagRepository.delete(tag);
        auditService.record(seriesId, "TAG_DELETE", "TAG", tagId, Map.of("name", tag.getName()));
    }

    @Transactional
    public List<TagView> setDocumentTags(Long documentId, SetTagsRequest request) {
        KbDocument document = documentRepository.findByIdAndDeletedAtIsNull(documentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.DOCUMENT_NOT_FOUND, "文档不存在"));
        permissionService.requireMaintain(document.getSeriesId());
        List<Long> tagIds = request.tagIds() == null ? List.of() : request.tagIds();
        List<KbTag> tags = tagRepository.findBySeriesIdOrderByIdAsc(document.getSeriesId());
        Map<Long, KbTag> byId = tags.stream().collect(java.util.stream.Collectors.toMap(KbTag::getId, t -> t));
        if (tagIds.stream().anyMatch(id -> !byId.containsKey(id))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "标签不属于当前知识库");
        }
        documentTagRepository.deleteByDocumentId(documentId);
        for (Long tagId : tagIds.stream().distinct().toList()) {
            KbDocumentTag link = new KbDocumentTag();
            link.setDocumentId(documentId);
            link.setTagId(tagId);
            link.setSeriesId(document.getSeriesId());
            documentTagRepository.save(link);
        }
        auditService.record(document.getSeriesId(), "DOCUMENT_TAGS", "DOCUMENT", documentId,
                Map.of("tagIds", tagIds));
        return tagIds.stream().distinct().map(byId::get)
                .map(t -> new TagView(t.getId(), t.getName(), t.getColor(),
                        documentTagRepository.findByTagId(t.getId()).size()))
                .toList();
    }

    public List<TagView> tagsOfDocument(Long documentId) {
        List<KbDocumentTag> links = documentTagRepository.findByDocumentId(documentId);
        if (links.isEmpty()) {
            return List.of();
        }
        Map<Long, KbTag> tags = tagRepository.findAllById(links.stream().map(KbDocumentTag::getTagId).toList())
                .stream().collect(java.util.stream.Collectors.toMap(KbTag::getId, t -> t));
        return links.stream().map(l -> tags.get(l.getTagId())).filter(java.util.Objects::nonNull)
                .map(t -> new TagView(t.getId(), t.getName(), t.getColor(), 0)).toList();
    }

    private String validateName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty() || value.length() > 20) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "标签名称需为 1-20 个字符");
        }
        return value;
    }

    private String normalizeColor(String color) {
        if (color == null || !color.matches("^#[0-9a-fA-F]{6}$")) {
            return "#1677ff";
        }
        return color;
    }
}
