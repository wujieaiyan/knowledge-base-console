package com.kbconsole.tag;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KbTagRepository extends JpaRepository<KbTag, Long> {
    List<KbTag> findBySeriesIdOrderByIdAsc(Long seriesId);

    Optional<KbTag> findByIdAndSeriesId(Long id, Long seriesId);

    boolean existsBySeriesIdAndName(Long seriesId, String name);

    void deleteBySeriesId(Long seriesId);
}
