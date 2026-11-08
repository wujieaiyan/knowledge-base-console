package com.kbconsole.task;

import com.kbconsole.common.TaskStatus;
import com.kbconsole.common.TaskType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "kb_ingest_task", indexes = {
        @Index(name = "idx_task_series_status", columnList = "series_id, status, created_at"),
        @Index(name = "idx_task_doc", columnList = "document_id, created_at")
}, uniqueConstraints = @UniqueConstraint(name = "uk_task_idempotency", columnNames = "idempotency_key"))
@Getter
@Setter
@NoArgsConstructor
public class KbIngestTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "series_id", nullable = false)
    private Long seriesId;

    @Column(name = "document_id")
    private Long documentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private TaskType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskStatus status = TaskStatus.QUEUED;

    @Column(nullable = false)
    private int progress;

    @Column(length = 32)
    private String stage;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "retry_of_task_id")
    private Long retryOfTaskId;

    @Column(name = "config_version_id")
    private Long configVersionId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Lob
    private String payload;

    @Lob
    @Column(name = "failed_items")
    private String failedItems;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "operator_id", nullable = false)
    private Long operatorId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void touch() {
        this.updatedAt = Instant.now();
    }
}
