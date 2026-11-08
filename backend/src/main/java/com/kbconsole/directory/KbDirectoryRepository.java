package com.kbconsole.directory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KbDirectoryRepository extends JpaRepository<KbDirectory, Long> {
    List<KbDirectory> findBySeriesIdOrderBySortOrderAscIdAsc(Long seriesId);

    Optional<KbDirectory> findByIdAndSeriesId(Long id, Long seriesId);

    Optional<KbDirectory> findFirstBySeriesIdAndRootTrue(Long seriesId);

    boolean existsBySeriesIdAndParentIdAndName(Long seriesId, Long parentId, String name);

    long countBySeriesIdAndParentId(Long seriesId, Long parentId);

    List<KbDirectory> findBySeriesIdAndPathStartingWith(Long seriesId, String pathPrefix);

    void deleteBySeriesId(Long seriesId);
}
