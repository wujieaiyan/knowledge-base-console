package com.kbconsole.admin;

import com.kbconsole.auth.Permissions;
import com.kbconsole.auth.RequirePermission;
import com.kbconsole.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/departments")
@RequiredArgsConstructor
@RequirePermission({Permissions.DEPARTMENT_READ, Permissions.DEPARTMENT_MANAGE})
public class AdminDepartmentController {
    private final AdminDepartmentService departmentService;

    @GetMapping
    public ApiResponse<List<AdminDepartmentService.DepartmentView>> list() {
        return ApiResponse.ok(departmentService.list());
    }

    @PostMapping
    @RequirePermission(Permissions.DEPARTMENT_MANAGE)
    public ApiResponse<AdminDepartmentService.DepartmentView> create(
            @Valid @RequestBody AdminDepartmentService.CreateDepartmentRequest request) {
        return ApiResponse.ok(departmentService.create(request));
    }

    @PutMapping("/{id}")
    @RequirePermission(Permissions.DEPARTMENT_MANAGE)
    public ApiResponse<AdminDepartmentService.DepartmentView> update(
            @PathVariable Long id,
            @Valid @RequestBody AdminDepartmentService.UpdateDepartmentRequest request) {
        return ApiResponse.ok(departmentService.update(id, request));
    }

    @PatchMapping("/{id}/status")
    @RequirePermission(Permissions.DEPARTMENT_MANAGE)
    public ApiResponse<AdminDepartmentService.DepartmentView> changeStatus(
            @PathVariable Long id,
            @RequestBody AdminDepartmentService.StatusRequest request) {
        return ApiResponse.ok(departmentService.changeStatus(id, request.active()));
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.DEPARTMENT_MANAGE)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        departmentService.delete(id);
        return ApiResponse.ok();
    }
}
