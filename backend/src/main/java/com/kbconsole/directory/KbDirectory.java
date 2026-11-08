package com.kbconsole.directory;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "kb_directory", uniqueConstraints = @UniqueConstraint(name = "uk_dir_sibling_name", columnNames = {"series_id", "parent_id", "name"}))
@Getter
@Setter
@NoArgsConstructor
public class KbDirectory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "series_id", nullable = false)
    private Long seriesId;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 512)
    private String path;

    @Column(nullable = false)
    private int depth;

    @Column(name = "is_root", nullable = false)
    private boolean root;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void touch() {
        this.updatedAt = Instant.now();
    }
}
