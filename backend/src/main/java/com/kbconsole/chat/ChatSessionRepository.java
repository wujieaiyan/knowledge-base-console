package com.kbconsole.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {
    List<ChatSession> findByUserIdOrderByUpdatedAtDescIdDesc(Long userId);

    Optional<ChatSession> findByIdAndUserId(Long id, Long userId);
}
