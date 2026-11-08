package com.kbconsole.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KbTaskLogRepository extends JpaRepository<KbTaskLog, Long> {
    List<KbTaskLog> findByTaskIdOrderByCreatedAtAsc(Long taskId);
}
