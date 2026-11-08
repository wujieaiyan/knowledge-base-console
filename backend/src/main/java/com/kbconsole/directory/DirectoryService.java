package com.kbconsole.directory;

import com.kbconsole.audit.AuditService;
import com.kbconsole.common.*;
import com.kbconsole.document.KbDocument;
import com.kbconsole.document.KbDocumentRepository;
import com.kbconsole.ingest.KbChunkRepository;
import com.kbconsole.member.PermissionService;
import com.kbconsole.series.SeriesService;
import com.kbconsole.tag.KbDocumentTagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DirectoryService {
    private final KbDirectoryRepository directoryRepository;
    private final KbDocumentRepository documentRepository;
    private final KbChunkRepository chunkRepository;
    private final KbDocumentTagRepository documentTagRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final SeriesService seriesService;

    @Value("${kb.max-directory-depth:5}")
    private int maxDepth;

    public record DirectoryNode(Long id, Long parentId, String name, String path, int depth, boolean root,
                                int sortOrder, long docCount, List<DirectoryNode> children) {
    }

    public record CreateDirectoryRequest(Long parentId, String name) {
    }

    public record RenameDirectoryRequest(String name) {
    }

    public record MoveDirectoryRequest(Long targetParentId) {
    }

    public List<DirectoryNode> tree(Long seriesId) {
        permissionService.requireRead(seriesId);
        List<KbDirectory> directories = directoryRepository.findBySeriesIdOrderBySortOrderAscIdAsc(seriesId);
        Map<Long, List<KbDirectory>> byParent = directories.stream()
                .filter(d -> d.getParentId() != null)
                .collect(Collectors.groupingBy(KbDirectory::getParentId));
        List<DirectoryNode> roots = new ArrayList<>();
        for (KbDirectory directory : directories) {
            if (directory.getParentId() == null) {
                roots.add(buildNode(directory, byParent));
            }
        }
        return roots;
    }

    @Transactional
    public DirectoryNode create(Long seriesId, CreateDirectoryRequest request) {
        CurrentUser user = UserContext.require();
        permissionService.requireMaintain(seriesId);
        KbDirectory parent = resolveParent(seriesId, request.parentId());
        String name = validateName(request.name());
        if (parent.getDepth() + 1 > maxDepth) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.DIRECTORY_DEPTH,
                    "目录层级不能超过 " + maxDepth + " 层");
        }
        ensureSiblingNameAvailable(seriesId, parent.getId(), name, null);
        KbDirectory directory = new KbDirectory();
        directory.setSeriesId(seriesId);
        directory.setParentId(parent.getId());
        directory.setName(name);
        directory.setRoot(false);
        directory.setDepth(parent.getDepth() + 1);
        directory.setPath("/");
        directory.setSortOrder((int) directoryRepository.countBySeriesIdAndParentId(seriesId, parent.getId()));
        directory.setCreatedBy(user.id());
        directoryRepository.save(directory);
        directory.setPath(parent.getPath() + directory.getId() + "/");
        directoryRepository.save(directory);
        auditService.record(seriesId, "DIRECTORY_CREATE", "DIRECTORY", directory.getId(), Map.of("name", name));
        return node(directory, List.of());
    }

    @Transactional
    public DirectoryNode rename(Long seriesId, Long directoryId, RenameDirectoryRequest request) {
        permissionService.requireMaintain(seriesId);
        KbDirectory directory = requireDirectory(seriesId, directoryId);
        if (directory.isRoot()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "根目录不可重命名");
        }
        String name = validateName(request.name());
        ensureSiblingNameAvailable(seriesId, directory.getParentId(), name, directory.getId());
        directory.setName(name);
        directoryRepository.save(directory);
        auditService.record(seriesId, "DIRECTORY_RENAME", "DIRECTORY", directoryId, Map.of("name", name));
        return node(directory, List.of());
    }

    @Transactional
    public void move(Long seriesId, Long directoryId, MoveDirectoryRequest request) {
        permissionService.requireMaintain(seriesId);
        KbDirectory directory = requireDirectory(seriesId, directoryId);
        if (directory.isRoot()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "根目录不可移动");
        }
        KbDirectory target = resolveParent(seriesId, request.targetParentId());
        if (target.getId().equals(directory.getId()) || target.getPath().startsWith(directory.getPath())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DIRECTORY_CYCLE, "不能移动到自身或子目录下");
        }
        ensureSiblingNameAvailable(seriesId, target.getId(), directory.getName(), directory.getId());
        List<KbDirectory> subtree = directoryRepository.findBySeriesIdAndPathStartingWith(seriesId, directory.getPath());
        int maxRelativeDepth = subtree.stream().mapToInt(d -> d.getDepth() - directory.getDepth()).max().orElse(0);
        int newDepth = target.getDepth() + 1;
        if (newDepth + maxRelativeDepth > maxDepth) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.DIRECTORY_DEPTH,
                    "移动后目录层级将超过 " + maxDepth + " 层");
        }
        String oldPrefix = directory.getPath();
        String newPrefix = target.getPath() + directory.getId() + "/";
        int delta = newDepth - directory.getDepth();
        for (KbDirectory node : subtree) {
            if (node.getId().equals(directory.getId())) {
                node.setParentId(target.getId());
            }
            node.setPath(newPrefix + node.getPath().substring(oldPrefix.length()));
            node.setDepth(node.getDepth() + delta);
            directoryRepository.save(node);
        }
        directory.setSortOrder((int) directoryRepository.countBySeriesIdAndParentId(seriesId, target.getId()));
        directoryRepository.save(directory);
        auditService.record(seriesId, "DIRECTORY_MOVE", "DIRECTORY", directoryId,
                Map.of("targetParentId", target.getId()));
    }

    @Transactional
    public void delete(Long seriesId, Long directoryId, String strategy, String confirmName) {
        permissionService.requireMaintain(seriesId);
        KbDirectory directory = requireDirectory(seriesId, directoryId);
        if (directory.isRoot()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "根目录不可删除");
        }
        List<KbDirectory> subtree = directoryRepository.findBySeriesIdAndPathStartingWith(seriesId, directory.getPath());
        List<KbDirectory> children = subtree.stream().filter(d -> !d.getId().equals(directory.getId())).toList();
        List<Long> subtreeIds = subtree.stream().map(KbDirectory::getId).toList();
        List<KbDocument> documents = documentRepository.findByDirectoryIdInAndDeletedAtIsNull(subtreeIds);
        boolean empty = children.isEmpty() && documents.isEmpty();
        if (empty) {
            directoryRepository.delete(directory);
            auditService.record(seriesId, "DIRECTORY_DELETE", "DIRECTORY", directoryId, Map.of());
            return;
        }
        if (strategy == null || strategy.isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DIRECTORY_NOT_EMPTY,
                    "目录非空，请选择移动到上级或级联删除");
        }
        if ("MOVE_TO_PARENT".equalsIgnoreCase(strategy)) {
            if (!children.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DIRECTORY_NOT_EMPTY,
                        "目录包含子目录，请先处理子目录或选择级联删除");
            }
            Long parentId = directory.getParentId();
            for (KbDocument document : documents) {
                document.setDirectoryId(parentId);
                documentRepository.save(document);
            }
            directoryRepository.delete(directory);
            auditService.record(seriesId, "DIRECTORY_DELETE", "DIRECTORY", directoryId,
                    Map.of("strategy", "MOVE_TO_PARENT", "moved", documents.size()));
            return;
        }
        if (!"CASCADE".equalsIgnoreCase(strategy)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "删除策略不合法");
        }
        if (confirmName == null || !confirmName.equals(directory.getName())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "请输入目录名称确认删除");
        }
        Instant now = Instant.now();
        for (KbDocument document : documents) {
            document.setStatus(DocStatus.DELETED);
            document.setDeletedAt(now);
            documentRepository.save(document);
            chunkRepository.deleteByDocumentId(document.getId());
            documentTagRepository.deleteByDocumentId(document.getId());
        }
        subtree.stream().sorted(Comparator.comparingInt(KbDirectory::getDepth).reversed())
                .forEach(directoryRepository::delete);
        seriesService.refreshCounters(seriesId);
        auditService.record(seriesId, "DIRECTORY_DELETE", "DIRECTORY", directoryId,
                Map.of("strategy", "CASCADE", "documents", documents.size()));
    }

    private DirectoryNode buildNode(KbDirectory directory, Map<Long, List<KbDirectory>> byParent) {
        List<DirectoryNode> children = byParent.getOrDefault(directory.getId(), List.of()).stream()
                .map(child -> buildNode(child, byParent)).toList();
        return node(directory, children);
    }

    private DirectoryNode node(KbDirectory directory, List<DirectoryNode> children) {
        long docCount = documentRepository.countByDirectoryIdAndDeletedAtIsNull(directory.getId());
        return new DirectoryNode(directory.getId(), directory.getParentId(), directory.getName(), directory.getPath(),
                directory.getDepth(), directory.isRoot(), directory.getSortOrder(), docCount, children);
    }

    private KbDirectory resolveParent(Long seriesId, Long parentId) {
        if (parentId == null) {
            return directoryRepository.findFirstBySeriesIdAndRootTrue(seriesId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.DIRECTORY_NOT_FOUND, "根目录不存在"));
        }
        return directoryRepository.findByIdAndSeriesId(parentId, seriesId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.DIRECTORY_NOT_FOUND, "目录不存在"));
    }

    private KbDirectory requireDirectory(Long seriesId, Long directoryId) {
        return directoryRepository.findByIdAndSeriesId(directoryId, seriesId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.DIRECTORY_NOT_FOUND, "目录不存在"));
    }

    private String validateName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty() || value.length() > 50) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "目录名称需为 1-50 个字符");
        }
        return value;
    }

    private void ensureSiblingNameAvailable(Long seriesId, Long parentId, String name, Long excludeId) {
        List<KbDirectory> siblings = directoryRepository.findBySeriesIdOrderBySortOrderAscIdAsc(seriesId).stream()
                .filter(d -> Objects.equals(d.getParentId(), parentId))
                .filter(d -> excludeId == null || !d.getId().equals(excludeId))
                .toList();
        if (siblings.stream().anyMatch(d -> d.getName().equalsIgnoreCase(name))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.DUPLICATE_DIRECTORY, "同级已存在同名目录");
        }
    }
}
