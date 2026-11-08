package com.kbconsole.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findBySessionIdOrderByIdAsc(Long sessionId);

    List<ChatMessage> findTop12BySessionIdOrderByIdDesc(Long sessionId);

    Optional<ChatMessage> findTopBySessionIdOrderByIdDesc(Long sessionId);

    long countBySessionId(Long sessionId);

    @Query("select m.sessionId, count(m) from ChatMessage m where m.sessionId in :sessionIds group by m.sessionId")
    List<Object[]> countBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    void deleteBySessionId(Long sessionId);
}
