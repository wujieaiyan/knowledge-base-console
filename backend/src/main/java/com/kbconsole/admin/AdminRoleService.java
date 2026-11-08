package com.kbconsole.admin;

import com.kbconsole.auth.*;
import com.kbconsole.common.ApiException;
import com.kbconsole.common.ErrorCodes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AdminRoleService {
    private final SysRoleRepository roleRepository;
    private final AppUserRepository userRepository;
    private final AuthorizationService authorizationService;

    public record RoleView(Long id, String code, String name, String description, List<String> permissions,
                           boolean builtin, long userCount, Instant createdAt, Instant updatedAt) {
    }

    public record PermissionView(String code, String group, String label) {
    }

    public record CreateRoleRequest(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,31}", message = "角色码需为 2-32 位大写字母、数字或下划线")
            String code,
            @NotBlank @Size(min = 2, max = 32, message = "角色名称需为 2-32 个字符") String name,
            @Size(max = 200, message = "角色说明不能超过 200 个字符") String description,
            List<String> permissions) {
    }

    public record UpdateRoleRequest(
            @NotBlank @Size(min = 2, max = 32, message = "角色名称需为 2-32 个字符") String name,
            @Size(max = 200, message = "角色说明不能超过 200 个字符") String description,
            List<String> permissions) {
    }

    public List<RoleView> list() {
        return roleRepository.findAllByOrderByBuiltinDescIdAsc().stream().map(this::view).toList();
    }

    public List<PermissionView> permissions() {
        return Permissions.CATALOG.stream()
                .map(item -> new PermissionView(item.code(), item.group(), item.label()))
                .toList();
    }

    @Transactional
    public RoleView create(CreateRoleRequest request) {
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (roleRepository.existsByCode(code)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DUPLICATE_ROLE, "角色码已存在");
        }
        validateName(request.name(), null);
        List<String> permissions = validatePermissions(request.permissions());

        SysRole role = new SysRole();
        role.setCode(code);
        role.setName(request.name().trim());
        role.setDescription(trimToNull(request.description()));
        role.setPermissionsJson(authorizationService.write(permissions));
        role.setBuiltin(false);
        role.setUpdatedAt(Instant.now());
        roleRepository.save(role);
        return view(role);
    }

    @Transactional
    public RoleView update(Long roleId, UpdateRoleRequest request) {
        SysRole role = require(roleId);
        if (role.isBuiltin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ROLE_IMMUTABLE, "内置角色不可修改");
        }
        validateName(request.name(), roleId);
        role.setName(request.name().trim());
        role.setDescription(trimToNull(request.description()));
        role.setPermissionsJson(authorizationService.write(validatePermissions(request.permissions())));
        role.setUpdatedAt(Instant.now());
        roleRepository.save(role);
        return view(role);
    }

    @Transactional
    public void delete(Long roleId) {
        SysRole role = require(roleId);
        if (role.isBuiltin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ROLE_IMMUTABLE, "内置角色不可删除");
        }
        if (userRepository.countByRoleCode(role.getCode()) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.ROLE_IN_USE, "仍有用户使用该角色，无法删除");
        }
        roleRepository.delete(role);
    }

    private List<String> validatePermissions(List<String> permissions) {
        if (permissions != null) {
            for (String permission : permissions) {
                if (!Permissions.known(permission)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION,
                            "未知的权限项：" + permission);
                }
            }
        }
        return List.copyOf(Permissions.sanitize(permissions));
    }

    private void validateName(String raw, Long selfId) {
        String name = raw == null ? "" : raw.trim();
        boolean duplicate = roleRepository.findAll().stream()
                .anyMatch(role -> !Objects.equals(role.getId(), selfId) && role.getName().equalsIgnoreCase(name));
        if (duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DUPLICATE_ROLE, "角色名称已存在");
        }
    }

    private SysRole require(Long roleId) {
        return roleRepository.findById(roleId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.ROLE_NOT_FOUND, "角色不存在"));
    }

    private RoleView view(SysRole role) {
        return new RoleView(role.getId(), role.getCode(), role.getName(), role.getDescription(),
                List.copyOf(authorizationService.parse(role.getPermissionsJson())), role.isBuiltin(),
                userRepository.countByRoleCode(role.getCode()), role.getCreatedAt(), role.getUpdatedAt());
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
