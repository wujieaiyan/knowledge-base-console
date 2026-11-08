package com.kbconsole.member;

import com.kbconsole.auth.AppUser;
import com.kbconsole.auth.AppUserRepository;
import com.kbconsole.audit.AuditService;
import com.kbconsole.common.*;
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
public class MemberService {
    private final KbMemberRepository memberRepository;
    private final AppUserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;

    public record MemberView(Long id, String subjectType, Long subjectId, String subjectName, String account,
                             Long deptId, String deptName, String role, String source, Instant grantedAt,
                             Integer memberCount) {
    }

    public record GrantRequest(String subjectType, Long subjectId, String role) {
    }

    public record GrantBatchRequest(List<GrantRequest> grants) {
    }

    public record UpdateRoleRequest(String role) {
    }

    public List<MemberView> list(Long seriesId) {
        permissionService.requireAdmin(seriesId);
        List<KbMember> members = memberRepository.findBySeriesIdOrderByCreatedAtAsc(seriesId);
        Map<Long, AppUser> users = userRepository.findAllById(members.stream()
                        .filter(m -> m.getSubjectType() == SubjectType.USER).map(KbMember::getSubjectId).toList())
                .stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));
        Map<Long, Department> departments = departmentRepository.findAll().stream()
                .collect(Collectors.toMap(Department::getId, Function.identity()));
        List<MemberView> result = new ArrayList<>();
        for (KbMember member : members) {
            if (member.getSubjectType() == SubjectType.USER) {
                AppUser user = users.get(member.getSubjectId());
                Department dept = user == null || user.getDeptId() == null ? null : departments.get(user.getDeptId());
                result.add(new MemberView(member.getId(), "USER", member.getSubjectId(),
                        user == null ? "未知用户" : user.getName(), user == null ? null : user.getAccount(),
                        user == null ? null : user.getDeptId(), dept == null ? null : dept.getName(),
                        member.getRole().name(), "个人", member.getCreatedAt(), null));
            } else {
                Department dept = departments.get(member.getSubjectId());
                int count = userRepository.findByDeptId(member.getSubjectId()).size();
                result.add(new MemberView(member.getId(), "DEPT", member.getSubjectId(),
                        dept == null ? "未知部门" : dept.getName(), null, member.getSubjectId(),
                        dept == null ? null : dept.getName(), member.getRole().name(), "部门", member.getCreatedAt(), count));
            }
        }
        return result;
    }

    @Transactional
    public List<MemberView> grant(Long seriesId, GrantBatchRequest request) {
        CurrentUser current = UserContext.require();
        permissionService.requireAdmin(seriesId);
        if (request.grants() == null || request.grants().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "请选择授权对象");
        }
        for (GrantRequest grant : request.grants()) {
            SubjectType subjectType;
            try {
                subjectType = SubjectType.valueOf(grant.subjectType().toUpperCase());
            } catch (Exception ex) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "授权对象类型不合法");
            }
            Role role = parseRole(grant.role());
            if (role == Role.OWNER) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "owner 只能通过转让流程设置");
            }
            if (grant.subjectId() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "授权对象不能为空");
            }
            if (subjectType == SubjectType.USER && userRepository.findById(grant.subjectId()).isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "用户不存在");
            }
            if (subjectType == SubjectType.DEPT && departmentRepository.findById(grant.subjectId()).isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "部门不存在");
            }
            KbMember member = memberRepository
                    .findBySeriesIdAndSubjectTypeAndSubjectId(seriesId, subjectType, grant.subjectId())
                    .orElseGet(() -> {
                        KbMember created = new KbMember();
                        created.setSeriesId(seriesId);
                        created.setSubjectType(subjectType);
                        created.setSubjectId(grant.subjectId());
                        created.setGrantedBy(current.id());
                        return created;
                    });
            member.setRole(role);
            member.setUpdatedAt(Instant.now());
            memberRepository.save(member);
        }
        auditService.record(seriesId, "MEMBER_GRANT", "MEMBER", null,
                Map.of("count", request.grants().size()));
        return list(seriesId);
    }

    @Transactional
    public MemberView updateRole(Long seriesId, Long memberId, UpdateRoleRequest request) {
        permissionService.requireAdmin(seriesId);
        KbMember member = memberRepository.findByIdAndSeriesId(memberId, seriesId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.VALIDATION, "成员不存在"));
        Role newRole = parseRole(request.role());
        if (newRole == Role.OWNER) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "owner 只能通过转让流程设置");
        }
        if (member.getRole() == Role.OWNER || member.getRole() == Role.ADMIN) {
            long adminCount = memberRepository.countBySeriesIdAndRoleIn(seriesId, List.of(Role.OWNER, Role.ADMIN));
            if (adminCount <= 1 && !newRole.atLeast(Role.ADMIN)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.LAST_OWNER, "必须先保留一名 owner 或 admin");
            }
        }
        member.setRole(newRole);
        memberRepository.save(member);
        auditService.record(seriesId, "MEMBER_UPDATE", "MEMBER", memberId, Map.of("role", newRole.name()));
        return list(seriesId).stream().filter(v -> v.id().equals(memberId)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.VALIDATION, "成员不存在"));
    }

    @Transactional
    public void remove(Long seriesId, Long memberId) {
        permissionService.requireAdmin(seriesId);
        KbMember member = memberRepository.findByIdAndSeriesId(memberId, seriesId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.VALIDATION, "成员不存在"));
        if (member.getRole() == Role.OWNER) {
            long ownerCount = memberRepository.countBySeriesIdAndRoleIn(seriesId, List.of(Role.OWNER));
            if (ownerCount <= 1) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.LAST_OWNER, "不能移除最后一名 owner");
            }
        }
        memberRepository.delete(member);
        auditService.record(seriesId, "MEMBER_REMOVE", "MEMBER", memberId, Map.of());
    }

    private Role parseRole(String value) {
        try {
            return Role.valueOf(value == null ? "" : value.toUpperCase());
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "角色不合法");
        }
    }
}
