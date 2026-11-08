package com.kbconsole.admin;

import com.kbconsole.auth.Permissions;
import com.kbconsole.auth.RequirePermission;
import com.kbconsole.common.ApiResponse;
import com.kbconsole.common.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@RequirePermission({Permissions.USER_READ, Permissions.USER_MANAGE})
public class AdminUserController {
    private final AdminUserService userService;

    @GetMapping
    public ApiResponse<PageResult<AdminUserService.UserView>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(userService.list(keyword, deptId, active, page, size));
    }

    @PostMapping
    @RequirePermission(Permissions.USER_MANAGE)
    public ApiResponse<AdminUserService.UserView> create(
            @Valid @RequestBody AdminUserService.CreateUserRequest request) {
        return ApiResponse.ok(userService.create(request));
    }

    @PutMapping("/{id}")
    @RequirePermission(Permissions.USER_MANAGE)
    public ApiResponse<AdminUserService.UserView> update(
            @PathVariable Long id,
            @Valid @RequestBody AdminUserService.UpdateUserRequest request) {
        return ApiResponse.ok(userService.update(id, request));
    }

    @PutMapping("/{id}/password")
    @RequirePermission(Permissions.USER_MANAGE)
    public ApiResponse<Void> resetPassword(
            @PathVariable Long id,
            @Valid @RequestBody AdminUserService.ResetPasswordRequest request) {
        userService.resetPassword(id, request);
        return ApiResponse.ok();
    }
}
