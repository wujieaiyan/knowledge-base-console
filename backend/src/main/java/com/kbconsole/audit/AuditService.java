package com.kbconsole.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbconsole.common.CurrentUser;
import com.kbconsole.common.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {
    private final KbAuditLogRepository repository;
    private final ObjectMapper objectMapper;

    public void record(Long seriesId, String action, String targetType, Long targetId, Map<String, Object> detail) {
        try {
            CurrentUser user = UserContext.get();
            if (user == null) {
                return;
            }
            KbAuditLog logEntry = new KbAuditLog();
            logEntry.setSeriesId(seriesId);
            logEntry.setOperatorId(user.id());
            logEntry.setAction(action);
            logEntry.setTargetType(targetType);
            logEntry.setTargetId(targetId);
            logEntry.setDetail(objectMapper.writeValueAsString(detail == null ? Map.of() : detail));
            repository.save(logEntry);
        } catch (Exception ex) {
            log.warn("Failed to write audit log", ex);
        }
    }
}
