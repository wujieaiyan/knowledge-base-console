package com.kbconsole.series;

import com.kbconsole.common.IndexStrategy;
import com.kbconsole.common.SeriesStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "kb_series")
@Getter
@Setter
@NoArgsConstructor
public class KbSeries {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 200)
    private String description;

    @Column(name = "dept_id", nullable = false)
    private Long deptId;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "index_strategy", nullable = false, length = 20)
    private IndexStrategy indexStrategy = IndexStrategy.HYBRID;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeriesStatus status = SeriesStatus.ACTIVE;

    @Column(name = "active_config_version_id")
    private Long activeConfigVersionId;

    @Column(name = "doc_count", nullable = false)
    private int docCount;

    @Column(name = "ready_doc_count", nullable = false)
    private int readyDocCount;

    @Column(name = "failed_doc_count", nullable = false)
    private int failedDocCount;

    @Column(name = "chunk_count", nullable = false)
    private long chunkCount;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PreUpdate
    public void touch() {
        this.updatedAt = Instant.now();
    }
}
