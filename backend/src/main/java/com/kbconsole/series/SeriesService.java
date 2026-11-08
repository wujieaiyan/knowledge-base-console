package com.kbconsole.series;

import com.kbconsole.audit.AuditService;
import com.kbconsole.auth.AppUser;
import com.kbconsole.auth.AppUserRepository;
import com.kbconsole.common.*;
import com.kbconsole.config.ConfigPayload;
import com.kbconsole.config.ConfigService;
import com.kbconsole.directory.KbDirectory;
import com.kbconsole.directory.KbDirectoryRepository;
import com.kbconsole.document.KbDocument;
import com.kbconsole.document.KbDocumentRepository;
import com.kbconsole.ingest.KbChunkRepository;
import com.kbconsole.integration.ElasticsearchService;
import com.kbconsole.member.KbMember;
import com.kbconsole.member.KbMemberRepository;
import com.kbconsole.member.PermissionService;
import com.kbconsole.org.Department;
import com.kbconsole.org.DepartmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SeriesService {
    private final KbSeriesRepository seriesRepository;
    private final KbMemberRepository memberRepository;
    private final KbDirectoryRepository directoryRepository;
    private final KbDocumentRepository documentRepository;
    private final KbChunkRepository chunkRepository;
    private final AppUserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final PermissionService permissionService;
    private final ConfigService configService;
    private final ElasticsearchService elasticsearchService;
    private final AuditService auditService;

    public PageResult<SeriesView> list(String scope, String keyword, Long deptId, String status,
                                       int page, int size) {
        CurrentUser user = UserContext.require();
        Map<Long, Role> roles = permissionService.rolesForUser(user);
        List<KbSeries> all = seriesRepository.findByDeletedAtIsNullOrderByUpdatedAtDesc();
        String keywordLower = keyword == null ? null : keyword.trim().toLowerCase(Locale.ROOT);
        List<KbSeries> filtered = new ArrayList<>();
        for (KbSeries series : all) {
            Role role = user.platformAdmin() ? Role.OWNER : roles.get(series.getId());
            if (role == null) {
                continue;
            }
            if ("maintainable".equalsIgnoreCase(scope) && !role.atLeast(Role.EDITOR)) {
                continue;
            }
            if (status != null && !status.isBlank() && !series.getStatus().name().equalsIgnoreCase(status)) {
                continue;
            }
            if (deptId != null && !deptId.equals(series.getDeptId())) {
                continue;
            }
            if (keywordLower != null && !keywordLower.isBlank()) {
                String haystack = (series.getName() + " " + (series.getDescription() == null ? "" : series.getDescription())).toLowerCase(Locale.ROOT);
                if (!haystack.contains(keywordLower)) {
                    continue;
                }
            }
            filtered.add(series);
        }
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(1, page);
        int from = Math.min((safePage - 1) * safeSize, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());
        List<SeriesView> items = toViewList(filtered.subList(from, to), user, roles);
        return new PageResult<>(items, filtered.size(), safePage, safeSize);
    }

    public SeriesView detail(Long seriesId) {
        CurrentUser user = UserContext.require();
        Role role = permissionService.requireRead(seriesId);
        KbSeries series = seriesRepository.findByIdAndDeletedAtIsNull(seriesId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.SERIES_NOT_FOUND, "知识库不存在"));
        return toView(series, role, user);
    }

    @Transactional
    public SeriesView create(CreateSeriesRequest request) {
        CurrentUser user = UserContext.require();
        Long deptId = request.getDeptId() == null ? user.deptId() : request.getDeptId();
        if (deptId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "请选择所属部门");
        }
        Department department = departmentRepository.findById(deptId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "所属部门不存在"));

        KbSeries series = new KbSeries();
        series.setName(request.getName().trim());
        series.setDescription(request.getDescription());
        series.setDeptId(deptId);
        series.setAuthorId(user.id());
        series.setStatus(Boolean.TRUE.equals(request.getDraft()) ? SeriesStatus.DRAFT : SeriesStatus.ACTIVE);
        seriesRepository.save(series);

        KbDirectory root = new KbDirectory();
        root.setSeriesId(series.getId());
        root.setParentId(null);
        root.setName("全部知识");
        root.setRoot(true);
        root.setDepth(0);
        root.setSortOrder(0);
        root.setPath("/");
        root.setCreatedBy(user.id());
        directoryRepository.save(root);
        root.setPath("/" + root.getId() + "/");
        directoryRepository.save(root);

        ConfigPayload config = request.getConfig() == null ? new ConfigPayload() : request.getConfig();
        configService.createVersion(series.getId(), request.getIndexStrategy(), config.getParse(),
                config.getChunk(), config.getRetrieval(), "初始化配置", user.id());

        KbMember owner = new KbMember();
        owner.setSeriesId(series.getId());
        owner.setSubjectType(SubjectType.USER);
        owner.setSubjectId(user.id());
        owner.setRole(Role.OWNER);
        owner.setGrantedBy(user.id());
        memberRepository.save(owner);

        auditService.record(series.getId(), "SERIES_CREATE", "SERIES", series.getId(),
                Map.of("name", series.getName(), "dept", department.getName()));
        KbSeries saved = seriesRepository.findById(series.getId()).orElse(series);
        return toView(saved, Role.OWNER, user);
    }

    @Transactional
    public SeriesView update(Long seriesId, UpdateSeriesRequest request) {
        CurrentUser user = UserContext.require();
        Role role = permissionService.requireAdmin(seriesId);
        KbSeries series = seriesRepository.findByIdAndDeletedAtIsNull(seriesId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.SERIES_NOT_FOUND, "知识库不存在"));
        ensureNotLocked(series);
        if (request.getRowVersion() != null && series.getRowVersion() != null
                && !request.getRowVersion().equals(series.getRowVersion())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.VERSION_CONFLICT, "知识库已被他人修改，请刷新后重试");
        }
        if (request.getName() != null) {
            String name = request.getName().trim();
            if (name.length() < 2 || name.length() > 50) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "名称需为 2-50 个字符");
            }
            series.setName(name);
        }
        if (request.getDescription() != null) {
            if (request.getDescription().length() > 200) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "简介不能超过 200 个字符");
            }
            series.setDescription(request.getDescription());
        }
        if (request.getDeptId() != null) {
            Department department = departmentRepository.findById(request.getDeptId())
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "所属部门不存在"));
            series.setDeptId(department.getId());
        }
        seriesRepository.save(series);
        auditService.record(seriesId, "SERIES_UPDATE", "SERIES", seriesId, Map.of("name", series.getName()));
        return toView(series, role, user);
    }

    @Transactional
    public void changeStatus(Long seriesId, boolean enable) {
        permissionService.requireAdmin(seriesId);
        KbSeries series = seriesRepository.findByIdAndDeletedAtIsNull(seriesId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.SERIES_NOT_FOUND, "知识库不存在"));
        ensureNotLocked(series);
        series.setStatus(enable ? SeriesStatus.ACTIVE : SeriesStatus.DISABLED);
        seriesRepository.save(series);
        auditService.record(seriesId, enable ? "SERIES_ENABLE" : "SERIES_DISABLE", "SERIES", seriesId, Map.of());
    }

    @Transactional
    public void delete(Long seriesId) {
        permissionService.requireOwner(seriesId);
        KbSeries series = seriesRepository.findByIdAndDeletedAtIsNull(seriesId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.SERIES_NOT_FOUND, "知识库不存在"));
        series.setStatus(SeriesStatus.DELETING);
        seriesRepository.save(series);

        List<KbDocument> documents = documentRepository.findBySeriesIdAndDeletedAtIsNullOrderByUpdatedAtDesc(seriesId);
        Instant now = Instant.now();
        for (KbDocument document : documents) {
            document.setStatus(DocStatus.DELETED);
            document.setDeletedAt(now);
            documentRepository.save(document);
        }
        chunkRepository.deleteBySeriesId(seriesId);
        TransactionHooks.afterCommit(() -> elasticsearchService.deleteBySeries(seriesId));
        series.setStatus(SeriesStatus.DELETED);
        series.setDeletedAt(now);
        seriesRepository.save(series);
        auditService.record(seriesId, "SERIES_DELETE", "SERIES", seriesId, Map.of("name", series.getName()));
    }

    public Map<String, Object> stats(Long seriesId) {
        permissionService.requireRead(seriesId);
        KbSeries series = seriesRepository.findByIdAndDeletedAtIsNull(seriesId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.SERIES_NOT_FOUND, "知识库不存在"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docCount", series.getDocCount());
        result.put("readyDocCount", series.getReadyDocCount());
        result.put("failedDocCount", series.getFailedDocCount());
        result.put("chunkCount", series.getChunkCount());
        result.put("configVersionId", series.getActiveConfigVersionId());
        return result;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void refreshCounters(Long seriesId) {
        if (seriesRepository.findById(seriesId).isEmpty()) {
            return;
        }
        int total = (int) documentRepository.countBySeriesIdAndDeletedAtIsNull(seriesId);
        int ready = (int) documentRepository.countBySeriesIdAndStatusAndDeletedAtIsNull(seriesId, DocStatus.READY);
        int failed = (int) documentRepository.countBySeriesIdAndStatusAndDeletedAtIsNull(seriesId, DocStatus.FAILED);
        long chunks = chunkRepository.countBySeriesId(seriesId);
        seriesRepository.updateCounters(seriesId, total, ready, failed, chunks, Instant.now());
    }

    private void ensureNotLocked(KbSeries series) {
        if (series.getStatus() == SeriesStatus.DELETING || series.getStatus() == SeriesStatus.DELETED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.SERIES_DELETING, "知识库正在删除中");
        }
    }

    private List<SeriesView> toViewList(List<KbSeries> seriesList, CurrentUser user, Map<Long, Role> roles) {
        if (seriesList.isEmpty()) {
            return List.of();
        }
        Map<Long, Department> departments = departmentRepository.findAll().stream()
                .collect(Collectors.toMap(Department::getId, Function.identity()));
        Set<Long> authorIds = seriesList.stream().map(KbSeries::getAuthorId).collect(Collectors.toSet());
        Map<Long, AppUser> authors = userRepository.findAllById(authorIds).stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity()));
        List<SeriesView> result = new ArrayList<>();
        for (KbSeries series : seriesList) {
            Role role = user.platformAdmin() ? Role.OWNER : roles.get(series.getId());
            Department dept = departments.get(series.getDeptId());
            AppUser author = authors.get(series.getAuthorId());
            result.add(new SeriesView(series.getId(), series.getName(), series.getDescription(),
                    series.getDeptId(), dept == null ? null : dept.getName(),
                    series.getAuthorId(), author == null ? null : author.getName(),
                    series.getStatus().name(), displayStatus(series),
                    series.getDocCount(), series.getReadyDocCount(), series.getFailedDocCount(),
                    series.getChunkCount(), series.getIndexStrategy().name(),
                    series.getActiveConfigVersionId(), role == null ? null : role.name(),
                    series.getRowVersion(), series.getCreatedAt(), series.getUpdatedAt()));
        }
        return result;
    }

    private SeriesView toView(KbSeries series, Role role, CurrentUser user) {
        Department dept = departmentRepository.findById(series.getDeptId()).orElse(null);
        AppUser author = userRepository.findById(series.getAuthorId()).orElse(null);
        return new SeriesView(series.getId(), series.getName(), series.getDescription(),
                series.getDeptId(), dept == null ? null : dept.getName(),
                series.getAuthorId(), author == null ? null : author.getName(),
                series.getStatus().name(), displayStatus(series),
                series.getDocCount(), series.getReadyDocCount(), series.getFailedDocCount(),
                series.getChunkCount(), series.getIndexStrategy().name(),
                series.getActiveConfigVersionId(), role == null ? null : role.name(),
                series.getRowVersion(), series.getCreatedAt(), series.getUpdatedAt());
    }

    private String displayStatus(KbSeries series) {
        if (series.getStatus() != SeriesStatus.ACTIVE) {
            return series.getStatus().name();
        }
        if (series.getFailedDocCount() > 0) {
            return "PARTIAL";
        }
        if (series.getDocCount() > 0 && series.getReadyDocCount() == 0) {
            return "INITIALIZING";
        }
        return "NORMAL";
    }
}
