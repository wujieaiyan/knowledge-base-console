package com.kbconsole.admin;

import com.kbconsole.auth.AppUserRepository;
import com.kbconsole.common.ApiException;
import com.kbconsole.common.ErrorCodes;
import com.kbconsole.org.Department;
import com.kbconsole.org.DepartmentRepository;
import com.kbconsole.series.KbSeriesRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminDepartmentService {
    private static final int MAX_DEPTH = 5;

    private final DepartmentRepository departmentRepository;
    private final AppUserRepository userRepository;
    private final KbSeriesRepository seriesRepository;

    public record DepartmentView(Long id, String name, Long parentId, int sortOrder, String description,
                                 boolean active, long userCount, long seriesCount) {
    }

    public record CreateDepartmentRequest(
            @NotBlank @Size(min = 2, max = 32, message = "部门名称需为 2-32 个字符") String name,
            Long parentId,
            Integer sortOrder,
            @Size(max = 200, message = "部门说明不能超过 200 个字符") String description) {
    }

    public record UpdateDepartmentRequest(
            @NotBlank @Size(min = 2, max = 32, message = "部门名称需为 2-32 个字符") String name,
            Long parentId,
            Integer sortOrder,
            @Size(max = 200, message = "部门说明不能超过 200 个字符") String description) {
    }

    public record StatusRequest(boolean active) {
    }

    public List<DepartmentView> list() {
        return departmentRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .map(this::view)
                .toList();
    }

    @Transactional
    public DepartmentView create(CreateDepartmentRequest request) {
        String name = validateName(request.name(), null);
        validateParent(null, request.parentId());
        Department department = new Department();
        department.setName(name);
        department.setParentId(request.parentId());
        department.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        department.setDescription(trimToNull(request.description()));
        department.setActive(Boolean.TRUE);
        departmentRepository.save(department);
        return view(department);
    }

    @Transactional
    public DepartmentView update(Long deptId, UpdateDepartmentRequest request) {
        Department department = require(deptId);
        String name = validateName(request.name(), deptId);
        validateParent(deptId, request.parentId());
        department.setName(name);
        department.setParentId(request.parentId());
        if (request.sortOrder() != null) {
            department.setSortOrder(request.sortOrder());
        }
        department.setDescription(trimToNull(request.description()));
        departmentRepository.save(department);
        return view(department);
    }

    @Transactional
    public DepartmentView changeStatus(Long deptId, boolean active) {
        Department department = require(deptId);
        department.setActive(active);
        departmentRepository.save(department);
        return view(department);
    }

    @Transactional
    public void delete(Long deptId) {
        Department department = require(deptId);
        List<Department> all = departmentRepository.findAll();
        boolean hasChildren = all.stream().anyMatch(item -> deptId.equals(item.getParentId()));
        if (hasChildren) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DEPARTMENT_IN_USE, "请先删除或移动下级部门");
        }
        if (userRepository.countByDeptId(deptId) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DEPARTMENT_IN_USE, "该部门下仍有用户，无法删除");
        }
        if (seriesRepository.countByDeptIdAndDeletedAtIsNull(deptId) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DEPARTMENT_IN_USE, "该部门仍被知识库引用，无法删除");
        }
        departmentRepository.delete(department);
    }

    private void validateParent(Long selfId, Long parentId) {
        if (parentId == null) {
            return;
        }
        Map<Long, Department> all = departmentRepository.findAll().stream()
                .collect(Collectors.toMap(Department::getId, Function.identity()));
        Department parent = all.get(parentId);
        if (parent == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_PARENT, "上级部门不存在");
        }
        if (!parent.isActive()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_PARENT, "上级部门已停用");
        }
        if (selfId != null) {
            if (selfId.equals(parentId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_PARENT, "上级部门不能是自身");
            }
            Long cursor = parent.getParentId();
            int guard = 0;
            while (cursor != null && guard++ < 20) {
                if (cursor.equals(selfId)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.DEPARTMENT_CYCLE, "不能移动到自己的下级部门");
                }
                Department node = all.get(cursor);
                cursor = node == null ? null : node.getParentId();
            }
        }
        int newDepth = depthOf(parentId, all) + 1;
        int height = selfId == null ? 1 : heightOf(selfId, all);
        if (newDepth + height - 1 > MAX_DEPTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.DEPARTMENT_DEPTH,
                    "部门层级最多 " + MAX_DEPTH + " 级");
        }
    }

    private int depthOf(Long deptId, Map<Long, Department> all) {
        int depth = 0;
        Long cursor = deptId;
        while (cursor != null && depth < 20) {
            depth++;
            Department node = all.get(cursor);
            cursor = node == null ? null : node.getParentId();
        }
        return depth;
    }

    private int heightOf(Long deptId, Map<Long, Department> all) {
        int height = 1;
        for (Department item : all.values()) {
            if (deptId.equals(item.getParentId())) {
                height = Math.max(height, 1 + heightOf(item.getId(), all));
            }
        }
        return height;
    }

    private String validateName(String raw, Long selfId) {
        String name = raw == null ? "" : raw.trim();
        boolean duplicate = departmentRepository.findAll().stream()
                .anyMatch(item -> !Objects.equals(item.getId(), selfId)
                        && item.getName().equalsIgnoreCase(name));
        if (duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.DUPLICATE_DEPARTMENT, "部门名称已存在");
        }
        return name;
    }

    private Department require(Long deptId) {
        return departmentRepository.findById(deptId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.DEPARTMENT_NOT_FOUND, "部门不存在"));
    }

    private DepartmentView view(Department department) {
        return new DepartmentView(department.getId(), department.getName(), department.getParentId(),
                department.getSortOrder(), department.getDescription(), department.isActive(),
                userRepository.countByDeptId(department.getId()),
                seriesRepository.countByDeptIdAndDeletedAtIsNull(department.getId()));
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
