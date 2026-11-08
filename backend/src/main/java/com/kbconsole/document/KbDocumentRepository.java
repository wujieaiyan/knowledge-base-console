package com.kbconsole.document;

import com.kbconsole.common.DocStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface KbDocumentRepository extends JpaRepository<KbDocument, Long> {
    List<KbDocument> findBySeriesIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long seriesId);

    Optional<KbDocument> findByIdAndDeletedAtIsNull(Long id);

    Optional<KbDocument> findBySeriesIdAndSha256AndDeletedAtIsNull(Long seriesId, String sha256);

    long countBySeriesIdAndDeletedAtIsNull(Long seriesId);

    long countBySeriesIdAndStatusAndDeletedAtIsNull(Long seriesId, DocStatus status);

    long countByDirectoryIdAndDeletedAtIsNull(Long directoryId);

    List<KbDocument> findByDirectoryIdAndDeletedAtIsNull(Long directoryId);

    List<KbDocument> findByDirectoryIdInAndDeletedAtIsNull(Collection<Long> directoryIds);

    List<KbDocument> findBySeriesIdAndStatusInAndDeletedAtIsNull(Long seriesId, Collection<DocStatus> statuses);

    void deleteBySeriesId(Long seriesId);
}
