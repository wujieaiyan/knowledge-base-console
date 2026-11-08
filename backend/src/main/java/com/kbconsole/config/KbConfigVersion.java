package com.kbconsole.config;

import com.kbconsole.common.IndexStrategy;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "kb_config_version", uniqueConstraints = @UniqueConstraint(name = "uk_config_series_version", columnNames = {"series_id", "version_no"}))
@Getter
@Setter
@NoArgsConstructor
public class KbConfigVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "series_id", nullable = false)
    private Long seriesId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "index_strategy", nullable = false, length = 20)
    private IndexStrategy indexStrategy;

    @Lob
    @Column(name = "parse_config", nullable = false)
    private String parseConfig;

    @Lob
    @Column(name = "chunk_config", nullable = false)
    private String chunkConfig;

    @Lob
    @Column(name = "retrieval_config", nullable = false)
    private String retrievalConfig;

    @Column(name = "change_note", length = 200)
    private String changeNote;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
