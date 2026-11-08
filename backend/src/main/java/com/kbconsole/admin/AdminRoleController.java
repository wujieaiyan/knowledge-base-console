package com.kbconsole.admin;

import com.kbconsole.auth.Permissions;
import com.kbconsole.auth.RequirePermission;
import com.kbconsole.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@RequirePermission({Permissions.ROLE_READ, Permissions.ROLE_MANAGE})
public class AdminRoleController {
    private final AdminRoleService roleService;

    @GetMapping("/roles")
    public ApiResponse<List<AdminRoleService.RoleView>> list() {
        return ApiResponse.ok(roleService.list());
    }

    @GetMapping("/permissions")
    public ApiResponse<List<AdminRoleService.PermissionView>> permissions() {
        return ApiResponse.ok(roleService.permissions());
    }

    @PostMapping("/roles")
    @RequirePermission(Permissions.ROLE_MANAGE)
    public ApiResponse<AdminRoleService.RoleView> create(
            @Valid @RequestBody AdminRoleService.CreateRoleRequest request) {
        return ApiResponse.ok(roleService.create(request));
    }

    @PutMapping("/roles/{id}")
    @RequirePermission(Permissions.ROLE_MANAGE)
    public ApiResponse<AdminRoleService.RoleView> update(
            @PathVariable Long id,
            @Valid @RequestBody AdminRoleService.UpdateRoleRequest request) {
        return ApiResponse.ok(roleService.update(id, request));
    }

    @DeleteMapping("/roles/{id}")
    @RequirePermission(Permissions.ROLE_MANAGE)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        roleService.delete(id);
        return ApiResponse.ok();
    }
}
