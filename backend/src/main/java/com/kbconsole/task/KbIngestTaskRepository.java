package com.kbconsole.task;

import com.kbconsole.common.TaskStatus;
import com.kbconsole.common.TaskType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KbIngestTaskRepository extends JpaRepository<KbIngestTask, Long> {
    List<KbIngestTask> findBySeriesIdOrderByCreatedAtDesc(Long seriesId);

    Optional<KbIngestTask> findByIdAndSeriesId(Long id, Long seriesId);

    Optional<KbIngestTask> findByIdempotencyKey(String idempotencyKey);

    List<KbIngestTask> findByDocumentIdOrderByCreatedAtDesc(Long documentId);

    List<KbIngestTask> findByStatusIn(List<TaskStatus> statuses);
}
