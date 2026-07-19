package com.finvoicebot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Everything a skill needs to act on a single user turn.
 *
 * userId       - identifies whose chat history / invoice history this belongs to.
 * sessionId    - groups messages into a conversation (sidebar "past chats").
 * message      - the raw text the user typed (or a synthesized command like "scan invoice").
 * invoiceImage - optional uploaded invoice image bytes, present only on scan requests.
 * imageFilename- original filename, for content-type sniffing and audit logs.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SkillRequest {
    private String userId;
    private String sessionId;
    private String message;
    private byte[] invoiceImage;
    private String imageFilename;

    public boolean hasImage() {
        return invoiceImage != null && invoiceImage.length > 0;
    }

    /** Lower-cased, trimmed message — the form skills should match against. */
    public String normalizedMessage() {
        return message == null ? "" : message.trim().toLowerCase();
    }
}
