package com.kbconsole.task;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "kb_task_log", indexes = @Index(name = "idx_task_log", columnList = "task_id, created_at"))
@Getter
@Setter
@NoArgsConstructor
public class KbTaskLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(nullable = false, length = 32)
    private String stage;

    @Column(nullable = false, length = 8)
    private String level = "INFO";

    @Column(length = 1000)
    private String message;

    @Column(name = "cost_ms")
    private Integer costMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
