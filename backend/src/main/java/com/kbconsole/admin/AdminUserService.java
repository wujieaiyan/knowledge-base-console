package com.kbconsole.admin;

import com.kbconsole.auth.AppUser;
import com.kbconsole.auth.AppUserRepository;
import com.kbconsole.auth.Permissions;
import com.kbconsole.auth.SysRole;
import com.kbconsole.auth.SysRoleRepository;
import com.kbconsole.common.*;
import com.kbconsole.org.Department;
import com.kbconsole.org.DepartmentRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminUserService {
    private final AppUserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final SysRoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public record UserView(Long id, String account, String name, Long deptId, String deptName,
                           String roleCode, String roleName, boolean platformAdmin, boolean active,
                           Instant createdAt, Instant updatedAt, Instant lastLoginAt) {
    }

    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = "[a-z0-9_.-]{3,32}", message = "账号需为 3-32 位小写字母、数字或 _ . -") String account,
            @NotBlank @Size(min = 2, max = 32, message = "姓名需为 2-32 个字符") String name,
            @NotBlank @Size(min = 6, max = 72, message = "密码需为 6-72 个字符") String password,
            Long deptId,
            @NotBlank String roleCode,
            Boolean platformAdmin) {
    }

    public record UpdateUserRequest(
            @NotBlank @Size(min = 2, max = 32, message = "姓名需为 2-32 个字符") String name,
            Long deptId,
            String roleCode,
            Boolean active,
            Boolean platformAdmin) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Size(min = 6, max = 72, message = "密码需为 6-72 个字符") String password) {
    }

    public PageResult<UserView> list(String keyword, Long deptId, Boolean active, int page, int size) {
        Map<Long, Department> departments = departmentRepository.findAll().stream()
                .collect(Collectors.toMap(Department::getId, Function.identity()));
        Map<String, SysRole> roles = roleRepository.findAll().stream()
                .collect(Collectors.toMap(SysRole::getCode, Function.identity()));
        String keywordLower = keyword == null || keyword.isBlank() ? null : keyword.trim().toLowerCase(Locale.ROOT);

        List<AppUser> filtered = new ArrayList<>();
        for (AppUser user : userRepository.findAll()) {
            if (active != null && user.isActive() != active) {
                continue;
            }
            if (deptId != null && !deptId.equals(user.getDeptId())) {
                continue;
            }
            if (keywordLower != null) {
                String haystack = (user.getName() + " " + user.getAccount()).toLowerCase(Locale.ROOT);
                if (!haystack.contains(keywordLower)) {
                    continue;
                }
            }
            filtered.add(user);
        }
        filtered.sort(Comparator.comparing(AppUser::getId));

        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(1, page);
        int from = Math.min((safePage - 1) * safeSize, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());
        List<UserView> items = filtered.subList(from, to).stream()
                .map(user -> view(user, departments.get(user.getDeptId()), roles.get(roleCodeOf(user))))
                .toList();
        return new PageResult<>(items, filtered.size(), safePage, safeSize);
    }

    @Transactional
    public UserView create(CreateUserRequest request) {
        CurrentUser me = UserContext.require();
        String account = request.account().trim().toLowerCase(Locale.ROOT);
        boolean exists = userRepository.findAll().stream()
                .anyMatch(user -> user.getAccount().equalsIgnoreCase(account));
        if (exists) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DUPLICATE_ACCOUNT, "账号已存在");
        }
        SysRole role = requireRole(request.roleCode());
        if (Permissions.ROLE_ADMIN.equals(role.getCode()) && !me.platformAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, "只有平台管理员可以授予平台管理员角色");
        }
        if (Boolean.TRUE.equals(request.platformAdmin()) && !me.platformAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, "只有平台管理员可以授予全库访问权限");
        }
        Department department = requireDepartment(request.deptId());

        AppUser user = new AppUser();
        user.setAccount(account);
        user.setName(request.name().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDeptId(department == null ? null : department.getId());
        user.setRoleCode(role.getCode());
        user.setPlatformAdmin(Boolean.TRUE.equals(request.platformAdmin()));
        user.setActive(Boolean.TRUE);
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        return view(user, department, role);
    }

    @Transactional
    public UserView update(Long userId, UpdateUserRequest request) {
        CurrentUser me = UserContext.require();
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.USER_NOT_FOUND, "用户不存在"));
        if (user.isPlatformAdmin() && !me.platformAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, "只有平台管理员可以维护平台管理员账号");
        }

        boolean self = user.getId().equals(me.id());
        boolean nextActive = request.active() == null ? user.isActive() : request.active();
        boolean nextPlatformAdmin = request.platformAdmin() == null
                ? user.isPlatformAdmin() : request.platformAdmin();
        String nextRoleCode = request.roleCode() == null || request.roleCode().isBlank()
                ? roleCodeOf(user) : request.roleCode().trim().toUpperCase(Locale.ROOT);

        if (self) {
            if (!nextActive) {
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.SELF_OPERATION, "不能停用当前登录账号");
            }
            if (!nextRoleCode.equals(roleCodeOf(user))) {
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.SELF_OPERATION, "不能修改自己的角色");
            }
            if (!nextPlatformAdmin) {
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.SELF_OPERATION, "不能取消自己的全库访问权限");
            }
        }
        SysRole role = requireRole(nextRoleCode);
        if (Permissions.ROLE_ADMIN.equals(role.getCode()) && !me.platformAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, "只有平台管理员可以授予平台管理员角色");
        }
        if (request.platformAdmin() != null && !me.platformAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, "只有平台管理员可以调整全库访问权限");
        }
        if (!nextActive || !nextPlatformAdmin) {
            ensureNotLastAdmin(user);
        }
        Department department = requireDepartment(request.deptId());

        user.setName(request.name().trim());
        user.setDeptId(department == null ? null : department.getId());
        user.setRoleCode(role.getCode());
        user.setActive(nextActive);
        user.setPlatformAdmin(nextPlatformAdmin);
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        return view(user, department, role);
    }

    @Transactional
    public void resetPassword(Long userId, ResetPasswordRequest request) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.USER_NOT_FOUND, "用户不存在"));
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
    }

    private void ensureNotLastAdmin(AppUser target) {
        if (!target.isPlatformAdmin()) {
            return;
        }
        boolean others = userRepository.findAll().stream()
                .anyMatch(user -> !user.getId().equals(target.getId()) && user.isPlatformAdmin() && user.isActive());
        if (!others) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.LAST_ADMIN, "至少需要保留一个启用的平台管理员");
        }
    }

    private SysRole requireRole(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION, "请选择角色");
        }
        return roleRepository.findByCode(roleCode.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.ROLE_NOT_FOUND, "角色不存在"));
    }

    private Department requireDepartment(Long deptId) {
        if (deptId == null) {
            return null;
        }
        return departmentRepository.findById(deptId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.DEPARTMENT_NOT_FOUND, "部门不存在"));
    }

    private String roleCodeOf(AppUser user) {
        if (user.isPlatformAdmin()) {
            return Permissions.ROLE_ADMIN;
        }
        return user.getRoleCode() == null || user.getRoleCode().isBlank()
                ? Permissions.ROLE_MEMBER : user.getRoleCode();
    }

    private UserView view(AppUser user, Department department, SysRole role) {
        String roleCode = roleCodeOf(user);
        return new UserView(user.getId(), user.getAccount(), user.getName(), user.getDeptId(),
                department == null ? null : department.getName(), roleCode,
                role == null ? roleCode : role.getName(), user.isPlatformAdmin(), user.isActive(),
                user.getCreatedAt(), user.getUpdatedAt(), user.getLastLoginAt());
    }
}
