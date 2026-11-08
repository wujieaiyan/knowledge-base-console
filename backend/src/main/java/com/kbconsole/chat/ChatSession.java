package com.kbconsole.chat;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "chat_session", indexes = {
        @Index(name = "idx_chat_session_user", columnList = "user_id,updated_at")
})
@Getter
@Setter
@NoArgsConstructor
public class ChatSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "series_id", nullable = false)
    private Long seriesId;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
