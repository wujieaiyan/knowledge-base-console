package com.kbconsole.tag;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "kb_document_tag", uniqueConstraints = @UniqueConstraint(name = "uk_doc_tag", columnNames = {"document_id", "tag_id"}))
@Getter
@Setter
@NoArgsConstructor
public class KbDocumentTag {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "tag_id", nullable = false)
    private Long tagId;

    @Column(name = "series_id", nullable = false)
    private Long seriesId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
