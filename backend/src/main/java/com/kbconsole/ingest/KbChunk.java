package com.kbconsole.ingest;

import com.kbconsole.common.ChunkLevel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "kb_chunk", indexes = {
        @Index(name = "idx_chunk_doc_seq", columnList = "document_id, seq"),
        @Index(name = "idx_chunk_series_status", columnList = "series_id, status")
})
@Getter
@Setter
@NoArgsConstructor
public class KbChunk {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "series_id", nullable = false)
    private Long seriesId;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "parent_chunk_id")
    private Long parentChunkId;

    @Enumerated(EnumType.STRING)
    @Column(name = "chunk_level", nullable = false, length = 10)
    private ChunkLevel chunkLevel = ChunkLevel.SINGLE;

    @Column(nullable = false)
    private int seq;

    @Lob
    @Column(nullable = false)
    private String content;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    @Column(name = "char_start")
    private Integer charStart;

    @Column(name = "char_end")
    private Integer charEnd;

    @Column(name = "page_no")
    private Integer pageNo;

    @Column(length = 200)
    private String bbox;

    @Column(name = "title_path", length = 512)
    private String titlePath;

    @Lob
    private String metadata;

    @Lob
    private String vector;

    @Column(nullable = false, length = 12)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
