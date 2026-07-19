package com.finvoicebot.skill.impl;

import com.finvoicebot.dto.ChatResponse;
import com.finvoicebot.dto.SkillRequest;
import com.finvoicebot.exception.SkillExecutionException;
import com.finvoicebot.model.PaymentRecord;
import com.finvoicebot.service.history.ChatHistoryService;
import com.finvoicebot.service.payment.PaymentGateway;
import com.finvoicebot.service.payment.PaymentGatewayFactory;
import com.finvoicebot.skill.ChatSkill;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trigger: "status" (optionally followed by a gateway reference id, e.g. "status order_ABC123").
 * Re-fetches live status from the gateway (rather than only reading the cached DB value) so the
 * user always sees the current state — captured/pending/failed — not a stale snapshot.
 */
@Component
@RequiredArgsConstructor
public class StatusSkill implements ChatSkill {

    private static final List<String> TRIGGERS = List.of("status", "payment status", "check payment");
    private static final Pattern REFERENCE_ID_PATTERN = Pattern.compile("status\\s+([A-Za-z0-9_\\-]{6,})", Pattern.CASE_INSENSITIVE);

    private final ChatHistoryService chatHistoryService;
    private final PaymentGatewayFactory paymentGatewayFactory;

    @Override
    public boolean canHandle(SkillRequest request) {
        String msg = request.normalizedMessage();
        return TRIGGERS.stream().anyMatch(msg::contains);
    }

    @Override
    public ChatResponse handle(SkillRequest request) {
        String explicitReferenceId = extractReferenceId(request.getMessage());

        PaymentRecord record;
        if (explicitReferenceId != null) {
            record = chatHistoryService.findPaymentByReference(explicitReferenceId)
                    .orElseThrow(() -> new SkillExecutionException(name(),
                            "I don't have a payment record for reference " + explicitReferenceId + "."));
        } else {
            record = chatHistoryService.lastPaymentForUser(request.getUserId())
                    .orElseThrow(() -> new SkillExecutionException(name(),
                            "No payments found yet — say \"scan invoice\" and attach one to get started."));
        }

        PaymentGateway gateway = paymentGatewayFactory.byType(record.getGateway());
        PaymentGateway.PaymentStatusResult liveStatus = gateway.checkStatus(record.getGatewayReferenceId());

        record.setStatus(liveStatus.status());
        record.setLastCheckedAt(java.time.Instant.now());
        record.setGatewayResponseJson(liveStatus.rawResponseJson());
        chatHistoryService.savePayment(record);

        String message = String.format(
                "Payment %s via %s is currently: %s (amount: %s %s).",
                record.getGatewayReferenceId(), record.getGateway(), liveStatus.status(),
                record.getCurrency(), record.getAmount());

        return ChatResponse.builder()
                .skillName(name())
                .message(message)
                .data(record)
                .success(true)
                .build();
    }

    private String extractReferenceId(String message) {
        if (message == null) return null;
        Matcher m = REFERENCE_ID_PATTERN.matcher(message);
        return m.find() ? m.group(1) : null;
    }

    @Override
    public String name() {
        return "StatusSkill";
    }

    @Override
    public String triggerDescription() {
        return "\"status\" (optionally + a reference id) — fetch the current payment status from the gateway";
    }

    @Override
    public int priority() {
        return 15;
    }
}
