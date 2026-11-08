package com.kbconsole.auth;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Permissions {
    public static final String USER_READ = "user:read";
    public static final String USER_MANAGE = "user:manage";
    public static final String DEPARTMENT_READ = "department:read";
    public static final String DEPARTMENT_MANAGE = "department:manage";
    public static final String ROLE_READ = "role:read";
    public static final String ROLE_MANAGE = "role:manage";
    public static final String CHAT_USE = "chat:use";
    public static final String KB_CREATE = "kb:create";

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_MANAGER = "MANAGER";
    public static final String ROLE_MEMBER = "MEMBER";

    public record Definition(String code, String group, String label) {
    }

    public static final List<Definition> CATALOG = List.of(
            new Definition(USER_READ, "用户管理", "查看用户"),
            new Definition(USER_MANAGE, "用户管理", "新增、编辑、停用用户"),
            new Definition(DEPARTMENT_READ, "部门管理", "查看部门"),
            new Definition(DEPARTMENT_MANAGE, "部门管理", "新增、编辑、启停、删除部门"),
            new Definition(ROLE_READ, "角色权限", "查看角色与权限"),
            new Definition(ROLE_MANAGE, "角色权限", "新增、编辑、删除角色"),
            new Definition(CHAT_USE, "知识问答", "使用智能问答与会话中心"),
            new Definition(KB_CREATE, "知识库", "新建知识库")
    );

    public static final List<String> ALL = CATALOG.stream().map(Definition::code).toList();

    public static final List<String> ADMIN = ALL;

    public static final List<String> MANAGER = List.of(
            USER_READ, USER_MANAGE, DEPARTMENT_READ, ROLE_READ, CHAT_USE, KB_CREATE
    );

    public static final List<String> MEMBER = List.of(CHAT_USE, KB_CREATE);

    private Permissions() {
    }

    public static boolean known(String code) {
        return CATALOG.stream().anyMatch(item -> item.code().equals(code));
    }

    public static Set<String> sanitize(Collection<String> codes) {
        Set<String> input = codes == null ? Set.of() : new LinkedHashSet<>(codes);
        Set<String> result = new LinkedHashSet<>();
        for (String code : ALL) {
            if (input.contains(code)) {
                result.add(code);
            }
        }
        return result;
    }

    public static List<String> builtin(String roleCode) {
        if (ROLE_ADMIN.equals(roleCode)) {
            return ADMIN;
        }
        if (ROLE_MANAGER.equals(roleCode)) {
            return MANAGER;
        }
        return MEMBER;
    }
}
