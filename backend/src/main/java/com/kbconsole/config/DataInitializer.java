package com.kbconsole.config;

import com.kbconsole.auth.AppUser;
import com.kbconsole.auth.AppUserRepository;
import com.kbconsole.auth.AuthorizationService;
import com.kbconsole.auth.Permissions;
import com.kbconsole.auth.SysRole;
import com.kbconsole.auth.SysRoleRepository;
import com.kbconsole.org.Department;
import com.kbconsole.org.DepartmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {
    private final DepartmentRepository departmentRepository;
    private final AppUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthorizationService authorizationService;

    @Override
    public void run(String... args) {
        seedRoles();
        if (departmentRepository.count() == 0) {
            Department hr = department("人力资源部", null, 1);
            Department payroll = department("薪酬绩效组", hr.getId(), 2);
            Department finance = department("财务部", null, 3);
            Department admin = department("行政部", null, 4);
            departmentRepository.saveAll(List.of(hr, payroll, finance, admin));
            log.info("Seeded departments: HR={}, payroll={}, finance={}, admin={}",
                    hr.getId(), payroll.getId(), finance.getId(), admin.getId());
        }

        if (userRepository.count() == 0) {
            List<Department> departments = departmentRepository.findAllByOrderBySortOrderAscIdAsc();
            Long hrId = departments.stream().filter(d -> d.getName().equals("人力资源部")).findFirst().map(Department::getId).orElse(null);
            Long financeId = departments.stream().filter(d -> d.getName().equals("财务部")).findFirst().map(Department::getId).orElse(null);
            userRepository.saveAll(List.of(
                    user("zhangsan", "张三", hrId, false),
                    user("lisi", "李四", hrId, false),
                    user("wangwu", "王五", financeId, false),
                    user("admin", "平台管理员", hrId, true)
            ));
            log.info("Seeded demo users: zhangsan/lisi/wangwu/admin, password=123456");
        }
        normalizeUsers();
    }

    private void seedRoles() {
        ensureBuiltinRole(Permissions.ROLE_ADMIN, "平台管理员", "系统全部管理权限", Permissions.ADMIN);
        ensureBuiltinRole(Permissions.ROLE_MANAGER, "部门管理员", "可管理用户，查看部门与角色", Permissions.MANAGER);
        ensureBuiltinRole(Permissions.ROLE_MEMBER, "普通成员", "可使用问答并新建知识库", Permissions.MEMBER);
    }

    private void ensureBuiltinRole(String code, String name, String description, List<String> permissions) {
        SysRole role = roleRepository.findByCode(code).orElseGet(SysRole::new);
        role.setCode(code);
        role.setName(name);
        role.setDescription(description);
        role.setBuiltin(true);
        role.setPermissionsJson(authorizationService.write(permissions));
        role.setUpdatedAt(Instant.now());
        if (role.getCreatedAt() == null) {
            role.setCreatedAt(Instant.now());
        }
        roleRepository.save(role);
    }

    private void normalizeUsers() {
        for (AppUser user : userRepository.findAll()) {
            boolean changed = false;
            boolean platformAdmin = user.isPlatformAdmin();
            String targetRole = platformAdmin ? Permissions.ROLE_ADMIN
                    : (user.getRoleCode() == null || user.getRoleCode().isBlank()
                    ? Permissions.ROLE_MEMBER : user.getRoleCode());
            if (!targetRole.equals(user.getRoleCode())) {
                user.setRoleCode(targetRole);
                changed = true;
            }
            if (user.getActive() == null) {
                user.setActive(Boolean.TRUE);
                changed = true;
            }
            if (changed) {
                userRepository.save(user);
            }
        }
    }

    private Department department(String name, Long parentId, int sort) {
        Department department = new Department();
        department.setName(name);
        department.setParentId(parentId);
        department.setSortOrder(sort);
        department.setActive(Boolean.TRUE);
        return departmentRepository.save(department);
    }

    private AppUser user(String account, String name, Long deptId, boolean platformAdmin) {
        AppUser user = new AppUser();
        user.setAccount(account);
        user.setName(name);
        user.setDeptId(deptId);
        user.setPlatformAdmin(platformAdmin);
        user.setRoleCode(platformAdmin ? Permissions.ROLE_ADMIN : Permissions.ROLE_MEMBER);
        user.setActive(Boolean.TRUE);
        user.setPasswordHash(passwordEncoder.encode("123456"));
        return user;
    }
}
