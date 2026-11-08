package com.kbconsole.org;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "org_department")
@Getter
@Setter
@NoArgsConstructor
public class Department {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(length = 200)
    private String description;

    @Column(name = "active")
    private Boolean active = Boolean.TRUE;

    public boolean isActive() {
        return active == null || active;
    }
}
