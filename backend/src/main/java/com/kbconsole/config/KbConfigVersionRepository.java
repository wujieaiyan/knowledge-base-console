package com.kbconsole.config;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KbConfigVersionRepository extends JpaRepository<KbConfigVersion, Long> {
    List<KbConfigVersion> findBySeriesIdOrderByVersionNoDesc(Long seriesId);

    Optional<KbConfigVersion> findFirstBySeriesIdOrderByVersionNoDesc(Long seriesId);

    Optional<KbConfigVersion> findByIdAndSeriesId(Long id, Long seriesId);
}
