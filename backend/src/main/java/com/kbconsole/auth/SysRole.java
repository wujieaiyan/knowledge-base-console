package com.kbconsole.auth;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "sys_role", uniqueConstraints = @UniqueConstraint(name = "uk_sys_role_code", columnNames = "code"))
@Getter
@Setter
@NoArgsConstructor
public class SysRole {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String code;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 200)
    private String description;

    @Column(name = "permissions_json", length = 2000)
    private String permissionsJson;

    @Column(nullable = false)
    private boolean builtin;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;
}
