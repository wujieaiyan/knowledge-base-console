package com.kbconsole.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbconsole.common.ApiException;
import com.kbconsole.common.CurrentUser;
import com.kbconsole.common.ErrorCodes;
import com.kbconsole.common.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorizationService {
    private final SysRoleRepository roleRepository;
    private final ObjectMapper objectMapper;

    public Set<String> permissionsOf(AppUser user) {
        if (user.isPlatformAdmin()) {
            return Set.copyOf(Permissions.ALL);
        }
        String roleCode = user.getRoleCode() == null || user.getRoleCode().isBlank()
                ? Permissions.ROLE_MEMBER : user.getRoleCode();
        return roleRepository.findByCode(roleCode)
                .map(role -> Permissions.sanitize(parse(role.getPermissionsJson())))
                .orElseGet(() -> Set.copyOf(Permissions.builtin(roleCode)));
    }

    public String roleCodeOf(AppUser user) {
        if (user.isPlatformAdmin()) {
            return Permissions.ROLE_ADMIN;
        }
        return user.getRoleCode() == null || user.getRoleCode().isBlank()
                ? Permissions.ROLE_MEMBER : user.getRoleCode();
    }

    public String roleNameOf(String roleCode) {
        return roleRepository.findByCode(roleCode).map(SysRole::getName).orElse(roleCode);
    }

    public void require(String permission) {
        CurrentUser user = UserContext.require();
        if (!user.permissions().contains(permission)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, "没有执行该操作的权限");
        }
    }

    public List<String> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception ex) {
            log.warn("角色权限 JSON 解析失败：{}", ex.getMessage());
            return List.of();
        }
    }

    public String write(List<String> permissions) {
        try {
            return objectMapper.writeValueAsString(Permissions.sanitize(permissions));
        } catch (Exception ex) {
            throw new IllegalStateException("角色权限序列化失败", ex);
        }
    }
}
