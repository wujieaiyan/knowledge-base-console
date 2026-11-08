package com.kbconsole.document;

import com.kbconsole.common.DocStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "kb_document", uniqueConstraints = @UniqueConstraint(name = "uk_doc_series_sha", columnNames = {"series_id", "sha256", "deleted_at"}))
@Getter
@Setter
@NoArgsConstructor
public class KbDocument {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "series_id", nullable = false)
    private Long seriesId;

    @Column(name = "directory_id", nullable = false)
    private Long directoryId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 16)
    private String ext;

    @Column(name = "mime_type", nullable = false, length = 128)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "char_count")
    private Long charCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocStatus status = DocStatus.PENDING;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "config_version_id")
    private Long configVersionId;

    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

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
