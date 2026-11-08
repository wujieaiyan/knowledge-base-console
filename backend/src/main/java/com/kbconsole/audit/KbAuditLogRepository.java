package com.kbconsole.audit;

import org.springframework.data.jpa.repository.JpaRepository;

public interface KbAuditLogRepository extends JpaRepository<KbAuditLog, Long> {
}
