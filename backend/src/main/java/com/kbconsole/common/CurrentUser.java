package com.kbconsole.common;

import java.util.Set;

public record CurrentUser(Long id, String name, String account, Long deptId, boolean platformAdmin,
                          String roleCode, Set<String> permissions) {
    public boolean hasPermission(String permission) {
        return platformAdmin || permissions.contains(permission);
    }
}
