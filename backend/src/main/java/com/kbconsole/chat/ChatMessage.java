package com.kbconsole.chat;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "chat_message", indexes = {
        @Index(name = "idx_chat_message_session", columnList = "session_id,id")
})
@Getter
@Setter
@NoArgsConstructor
public class ChatMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(nullable = false, length = 16)
    private String role;

    @Lob
    @Column(nullable = false)
    private String content;

    @Lob
    @Column(name = "citations_json")
    private String citationsJson;

    @Lob
    @Column(name = "meta_json")
    private String metaJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
