package com.kbconsole.org;

import com.kbconsole.auth.AppUser;
import com.kbconsole.auth.AppUserRepository;
import com.kbconsole.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/org")
@RequiredArgsConstructor
public class OrgController {
    private final DepartmentRepository departmentRepository;
    private final AppUserRepository userRepository;

    public record DepartmentDto(Long id, String name, Long parentId, int sortOrder) {
    }

    public record UserDto(Long id, String name, String account, Long deptId, String deptName) {
    }

    @GetMapping("/departments/tree")
    public ApiResponse<List<DepartmentDto>> departments() {
        return ApiResponse.ok(departmentRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .map(d -> new DepartmentDto(d.getId(), d.getName(), d.getParentId(), d.getSortOrder()))
                .toList());
    }

    @GetMapping("/users")
    public ApiResponse<List<UserDto>> users(@RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) Long deptId) {
        List<AppUser> users;
        if (keyword != null && !keyword.isBlank()) {
            users = userRepository.findByNameContainingIgnoreCaseOrAccountContainingIgnoreCase(keyword.trim(), keyword.trim());
            if (deptId != null) {
                users = users.stream().filter(u -> deptId.equals(u.getDeptId())).toList();
            }
        } else if (deptId != null) {
            users = userRepository.findByDeptId(deptId);
        } else {
            users = userRepository.findAll();
        }
        Map<Long, Department> departments = departmentRepository.findAll().stream()
                .collect(Collectors.toMap(Department::getId, Function.identity()));
        List<UserDto> result = users.stream()
                .limit(100)
                .map(u -> new UserDto(u.getId(), u.getName(), u.getAccount(), u.getDeptId(),
                        u.getDeptId() == null ? null : departments.getOrDefault(u.getDeptId(), new Department()).getName()))
                .toList();
        return ApiResponse.ok(result);
    }

    @GetMapping("/departments/{deptId}/members")
    public ApiResponse<List<UserDto>> departmentMembers(@PathVariable Long deptId) {
        Map<Long, Department> departments = departmentRepository.findAll().stream()
                .collect(Collectors.toMap(Department::getId, Function.identity()));
        List<UserDto> users = userRepository.findByDeptId(deptId).stream()
                .map(u -> new UserDto(u.getId(), u.getName(), u.getAccount(), u.getDeptId(),
                        departments.getOrDefault(deptId, new Department()).getName()))
                .toList();
        return ApiResponse.ok(users);
    }
}
