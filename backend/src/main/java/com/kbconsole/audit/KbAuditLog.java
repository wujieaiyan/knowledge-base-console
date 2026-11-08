package com.kbconsole.audit;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "kb_audit_log", indexes = @Index(name = "idx_audit_series_time", columnList = "series_id, created_at"))
@Getter
@Setter
@NoArgsConstructor
public class KbAuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "series_id")
    private Long seriesId;

    @Column(name = "operator_id", nullable = false)
    private Long operatorId;

    @Column(nullable = false, length = 48)
    private String action;

    @Column(name = "target_type", nullable = false, length = 24)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Lob
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
