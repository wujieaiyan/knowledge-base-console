package com.kbconsole.series;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface KbSeriesRepository extends JpaRepository<KbSeries, Long> {
    List<KbSeries> findByDeletedAtIsNullOrderByUpdatedAtDesc();

    Optional<KbSeries> findByIdAndDeletedAtIsNull(Long id);

    long countByDeptIdAndDeletedAtIsNull(Long deptId);

    @Modifying(clearAutomatically = true)
    @Query("update KbSeries s set s.docCount = :docCount, s.readyDocCount = :readyDocCount, "
            + "s.failedDocCount = :failedDocCount, s.chunkCount = :chunkCount, s.updatedAt = :now "
            + "where s.id = :seriesId")
    int updateCounters(@Param("seriesId") Long seriesId,
                       @Param("docCount") int docCount,
                       @Param("readyDocCount") int readyDocCount,
                       @Param("failedDocCount") int failedDocCount,
                       @Param("chunkCount") long chunkCount,
                       @Param("now") Instant now);
}
