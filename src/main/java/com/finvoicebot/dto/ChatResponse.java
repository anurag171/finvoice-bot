package com.finvoicebot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Uniform response envelope every skill returns, rendered by the UI as a chat bubble.
 *
 * skillName    - which skill produced this (shown in the UI + audit log for traceability).
 * message      - human-readable reply text.
 * data         - optional structured payload (e.g. ParsedInvoice, PaymentResult) serialized as-is;
 *                the frontend renders it as a details card when present.
 * audioUrl     - optional URL to a generated TTS confirmation clip.
 * invoiceId    - optional invoice ID (set by ScanInvoiceSkill, used by PaymentSkill to link payments).
 * success      - whether the skill completed without error.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatResponse {
    private String skillName;
    private String message;
    private Object data;
    private String audioUrl;
    private Long invoiceId;
    @Builder.Default
    private boolean success = true;

    public static ChatResponse of(String skillName, String message) {
        return ChatResponse.builder().skillName(skillName).message(message).success(true).build();
    }

    public static ChatResponse error(String skillName, String message) {
        return ChatResponse.builder().skillName(skillName).message(message).success(false).build();
    }
}
