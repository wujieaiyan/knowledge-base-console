package com.kbconsole.auth;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "app_user", uniqueConstraints = @UniqueConstraint(name = "uk_app_user_account", columnNames = "account"))
@Getter
@Setter
@NoArgsConstructor
public class AppUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String account;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "dept_id")
    private Long deptId;

    @Column(name = "platform_admin", nullable = false)
    private boolean platformAdmin;

    @Column(name = "role_code", length = 32)
    private String roleCode;

    @Column(name = "active")
    private Boolean active = Boolean.TRUE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    public boolean isActive() {
        return active == null || active;
    }
}
