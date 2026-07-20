package com.finvoicebot.skill.impl;

import com.finvoicebot.dto.ChatResponse;
import com.finvoicebot.dto.SkillRequest;
import com.finvoicebot.exception.SkillExecutionException;
import com.finvoicebot.model.InvoiceRecord;
import com.finvoicebot.model.PaymentRecord;
import com.finvoicebot.service.history.ChatHistoryService;
import com.finvoicebot.service.tts.TextToSpeechService;
import com.finvoicebot.skill.ChatSkill;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Trigger: "read aloud". Synthesizes (or re-synthesizes) an audio confirmation for the most
 * recent invoice in the current session — useful when the user missed the audio the first time,
 * or wants to replay it after checking status.
 */
@Component
@RequiredArgsConstructor
public class ReadAloudSkill implements ChatSkill {

    private static final List<String> TRIGGERS = List.of("read aloud", "read it aloud", "play confirmation", "speak");

    private final ChatHistoryService chatHistoryService;
    private final TextToSpeechService ttsService;

    @Override
    public boolean canHandle(SkillRequest request) {
        String msg = request.normalizedMessage();
        return TRIGGERS.stream().anyMatch(msg::contains);
    }

    @Override
    public ChatResponse handle(SkillRequest request) {
        Optional<InvoiceRecord> invoiceOpt = chatHistoryService.lastInvoiceForSession(request.getSessionId())
                .or(() -> chatHistoryService.lastInvoiceForUser(request.getUserId()));

        InvoiceRecord invoice = invoiceOpt.orElseThrow(() -> new SkillExecutionException(
                name(), "I don't have any scanned invoice yet — say \"scan invoice\" and attach one first."));

        Optional<PaymentRecord> paymentOpt = chatHistoryService.lastPaymentForInvoice(invoice.getId());

        String text = paymentOpt
                .map(p -> "Payment of %s %s to %s for invoice %s was submitted to %s. Current status: %s."
                        .formatted(
                                invoice.getCurrency(),
                                invoice.getAmount(),
                                invoice.getPayeeName(),
                                invoice.getInvoiceNumber(),
                                p.getGateway(),
                                p.getStatus()))
                .orElse("Invoice %s from %s for %s %s was scanned, but no payment has been triggered for it yet."
                        .formatted(
                                invoice.getInvoiceNumber(),
                                invoice.getPayeeName(),
                                invoice.getCurrency(),
                                invoice.getAmount()));

        String audioUrl = ttsService.synthesizeToFile(text);

        return ChatResponse.builder()
                .skillName(name())
                .message("Here's the audio confirmation for invoice %s.".formatted(invoice.getInvoiceNumber()))
                .audioUrl(audioUrl)
                .success(true)
                .build();
    }

    @Override
    public String name() {
        return "ReadAloudSkill";
    }

    @Override
    public String triggerDescription() {
        return "\"read aloud\" — hear a spoken confirmation for the last scanned invoice";
    }

    @Override
    public int priority() {
        return 20;
    }
}