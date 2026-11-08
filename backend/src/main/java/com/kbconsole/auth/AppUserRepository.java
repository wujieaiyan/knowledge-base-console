package com.kbconsole.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByAccount(String account);

    List<AppUser> findByDeptId(Long deptId);

    List<AppUser> findByNameContainingIgnoreCaseOrAccountContainingIgnoreCase(String name, String account);

    long countByDeptId(Long deptId);

    long countByRoleCode(String roleCode);
}
