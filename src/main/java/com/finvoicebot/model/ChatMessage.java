package com.finvoicebot.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "chat_message", indexes = {
        @Index(name = "idx_chat_session", columnList = "sessionId"),
        @Index(name = "idx_chat_user", columnList = "userId")
})
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String sessionId;

    @Column(nullable = false)
    private boolean fromUser;

    @Lob
    @Column(nullable = false)
    private String content;

    /** Name of the skill that produced this message, null for user turns. */
    private String skillName;

    /** JSON blob of any structured data attached to a bot response (e.g. ParsedInvoice, payment result). */
    @Lob
    private String dataJson;

    private String audioUrl;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
