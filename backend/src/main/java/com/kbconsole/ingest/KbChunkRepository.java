package com.kbconsole.ingest;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KbChunkRepository extends JpaRepository<KbChunk, Long> {
    List<KbChunk> findByDocumentIdOrderBySeqAsc(Long documentId);

    List<KbChunk> findBySeriesIdAndStatus(Long seriesId, String status);

    List<KbChunk> findByStatus(String status);

    long countBySeriesId(Long seriesId);

    void deleteByDocumentId(Long documentId);

    void deleteBySeriesId(Long seriesId);
}
