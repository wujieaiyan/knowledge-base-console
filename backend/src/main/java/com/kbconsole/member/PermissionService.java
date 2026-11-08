package com.kbconsole.member;

import com.kbconsole.auth.AppUser;
import com.kbconsole.auth.AppUserRepository;
import com.kbconsole.common.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PermissionService {
    private final KbMemberRepository memberRepository;
    private final AppUserRepository userRepository;

    public Role roleInSeries(Long seriesId, CurrentUser user) {
        if (user == null) {
            return null;
        }
        if (user.platformAdmin()) {
            return Role.OWNER;
        }
        Role best = null;
        best = max(best, memberRepository
                .findBySeriesIdAndSubjectTypeAndSubjectId(seriesId, SubjectType.USER, user.id())
                .map(KbMember::getRole).orElse(null));
        Long deptId = user.deptId();
        if (deptId == null) {
            deptId = userRepository.findById(user.id()).map(AppUser::getDeptId).orElse(null);
        }
        if (deptId != null) {
            best = max(best, memberRepository
                    .findBySeriesIdAndSubjectTypeAndSubjectId(seriesId, SubjectType.DEPT, deptId)
                    .map(KbMember::getRole).orElse(null));
        }
        return best;
    }

    public Map<Long, Role> rolesForUser(CurrentUser user) {
        Map<Long, Role> roles = new HashMap<>();
        if (user == null) {
            return roles;
        }
        if (user.platformAdmin()) {
            return roles;
        }
        memberRepository.findBySubjectTypeAndSubjectId(SubjectType.USER, user.id())
                .forEach(m -> roles.merge(m.getSeriesId(), m.getRole(), PermissionService::maxRole));
        Long deptId = user.deptId();
        if (deptId == null) {
            deptId = userRepository.findById(user.id()).map(AppUser::getDeptId).orElse(null);
        }
        if (deptId != null) {
            memberRepository.findBySubjectTypeAndSubjectId(SubjectType.DEPT, deptId)
                    .forEach(m -> roles.merge(m.getSeriesId(), m.getRole(), PermissionService::maxRole));
        }
        return roles;
    }

    public Role requireRead(Long seriesId) {
        CurrentUser user = UserContext.require();
        Role role = roleInSeries(seriesId, user);
        if (role == null) {
            throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
                    ErrorCodes.SERIES_NOT_FOUND, "知识库不存在或无权访问");
        }
        return role;
    }

    public Role requireMaintain(Long seriesId) {
        Role role = requireRead(seriesId);
        if (!role.atLeast(Role.EDITOR)) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                    ErrorCodes.FORBIDDEN, "没有该知识库的维护权限");
        }
        return role;
    }

    public Role requireAdmin(Long seriesId) {
        Role role = requireRead(seriesId);
        if (!role.atLeast(Role.ADMIN)) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                    ErrorCodes.FORBIDDEN, "没有该知识库的管理权限");
        }
        return role;
    }

    public Role requireOwner(Long seriesId) {
        Role role = requireRead(seriesId);
        if (!role.atLeast(Role.OWNER)) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                    ErrorCodes.FORBIDDEN, "只有 owner 可以执行该操作");
        }
        return role;
    }

    public boolean canMaintain(Long seriesId, CurrentUser user) {
        Role role = roleInSeries(seriesId, user);
        return role != null && role.atLeast(Role.EDITOR);
    }

    private static Role max(Role a, Role b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.atLeast(b) ? a : b;
    }

    private static Role maxRole(Role a, Role b) {
        return max(a, b);
    }
}
