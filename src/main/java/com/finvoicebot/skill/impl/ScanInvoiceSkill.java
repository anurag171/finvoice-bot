package com.finvoicebot.skill.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finvoicebot.dto.ChatResponse;
import com.finvoicebot.dto.ParsedInvoice;
import com.finvoicebot.dto.SkillRequest;
import com.finvoicebot.exception.SkillExecutionException;
import com.finvoicebot.model.GatewayType;
import com.finvoicebot.model.InvoiceRecord;
import com.finvoicebot.model.PaymentRecord;
import com.finvoicebot.service.history.ChatHistoryService;
import com.finvoicebot.service.ocr.OcrService;
import com.finvoicebot.service.parser.InvoiceParserService;
import com.finvoicebot.service.payment.PaymentGateway;
import com.finvoicebot.service.payment.PaymentGatewayFactory;
import com.finvoicebot.service.tts.TextToSpeechService;
import com.finvoicebot.skill.ChatSkill;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * Trigger: "scan invoice" (with an attached invoice image).
 *
 * <p>Orchestrates the full pipeline in one skill, in order:
 * OCR (Vision) -&gt; Parser (regex/heuristics) -&gt; Payment (Razorpay/Stripe) -&gt; TTS (confirmation audio).
 *
 * <p>Every stage's output is persisted via {@link ChatHistoryService} before moving to the next,
 * so a failure partway through (e.g. gateway rejects the payment) still leaves an audit trail of
 * what OCR/parsing produced.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScanInvoiceSkill implements ChatSkill {

    private static final List<String> TRIGGERS = List.of("scan invoice", "scan the invoice", "process invoice");

    private final OcrService ocrService;
    private final InvoiceParserService parserService;
    private final PaymentGatewayFactory paymentGatewayFactory;
    private final TextToSpeechService ttsService;
    private final ChatHistoryService chatHistoryService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean canHandle(SkillRequest request) {
        if (!request.hasImage()) {
            // Only claim the message if it both names this skill AND has an attachment;
            // otherwise let the router fall through to Help, which explains the image is required.
            return false;
        }
        String msg = request.normalizedMessage();
        return TRIGGERS.stream().anyMatch(msg::contains);
    }

    @Override
    public ChatResponse handle(SkillRequest request) {
        // 1) OCR
        String rawText = ocrService.extractText(request.getInvoiceImage(), request.getImageFilename());

        // 2) Parse
        ParsedInvoice parsed = parserService.parse(rawText);
        InvoiceRecord invoiceRecord = chatHistoryService.saveInvoice(request, parsed, request.getImageFilename());

        if (!parsed.hasMinimumFieldsForPayment()) {
            String msg = String.format(
                    "I scanned the invoice but could only extract %.0f%% of the required fields "
                            + "(invoice number, amount, payee). I couldn't confidently trigger payment — "
                            + "here's what I found:\n%s",
                    parsed.getParseConfidence() * 100, describeParsed(parsed));
            return ChatResponse.builder()
                    .skillName(name())
                    .message(msg)
                    .data(parsed)
                    .success(false)
                    .build();
        }

        // 3) Payment
        PaymentGateway gateway = paymentGatewayFactory.resolve(request.getMessage());
        PaymentGateway.PaymentInitiationResult paymentResult;
        try {
            paymentResult = gateway.initiatePayment(parsed);
        } catch (SkillExecutionException e) {
            // Persist the failed attempt for audit even though it didn't succeed.
            chatHistoryService.savePayment(PaymentRecord.builder()
                    .userId(request.getUserId())
                    .invoiceRecordId(invoiceRecord.getId())
                    .gateway(gateway.type())
                    .gatewayReferenceId("FAILED")
                    .amount(parsed.getAmount())
                    .currency(parsed.getCurrency())
                    .status("FAILED")
                    .gatewayResponseJson(e.getMessage())
                    .build());
            throw e;
        }

        PaymentRecord paymentRecord = chatHistoryService.savePayment(PaymentRecord.builder()
                .userId(request.getUserId())
                .invoiceRecordId(invoiceRecord.getId())
                .gateway(gateway.type())
                .gatewayReferenceId(paymentResult.gatewayReferenceId())
                .amount(parsed.getAmount())
                .currency(parsed.getCurrency())
                .status(paymentResult.status())
                .gatewayResponseJson(paymentResult.rawResponseJson())
                .build());

        // 4) TTS confirmation
        String confirmationText = buildConfirmationSpeech(parsed, gateway.type(), paymentResult.status());
        String audioUrl;
        try {
            audioUrl = ttsService.synthesizeToFile(confirmationText);
        } catch (SkillExecutionException e) {
            // Payment already succeeded — don't fail the whole turn just because audio synthesis failed.
            log.warn("TTS synthesis failed after successful payment {}: {}", paymentRecord.getId(), e.getMessage());
            audioUrl = null;
        }

        String userMessage = String.format(
                "Invoice %s for %s %s from %s processed via %s. Gateway reference: %s (status: %s).",
                parsed.getInvoiceNumber(), parsed.getCurrency(), formatAmount(parsed.getAmount()),
                parsed.getPayeeName(), gateway.type(), paymentResult.gatewayReferenceId(), paymentResult.status());

        return ChatResponse.builder()
                .skillName(name())
                .message(userMessage)
                .data(parsed)
                .audioUrl(audioUrl)
                .success(true)
                .build();
    }

    private String buildConfirmationSpeech(ParsedInvoice invoice, GatewayType gateway, String status) {
        return String.format(
                "Payment of %s %s to %s for invoice %s has been submitted to %s. Current status: %s.",
                invoice.getCurrency(), formatAmount(invoice.getAmount()), invoice.getPayeeName(),
                invoice.getInvoiceNumber(), gateway, status);
    }

    private String formatAmount(java.math.BigDecimal amount) {
        if (amount == null) return "unknown";
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return nf.format(amount);
    }

    private String describeParsed(ParsedInvoice p) {
        return String.format(
                "- Invoice #: %s%n- Amount: %s %s%n- Date: %s%n- Payee: %s",
                nullSafe(p.getInvoiceNumber()), nullSafe(p.getCurrency()), p.getAmount() == null ? "—" : p.getAmount(),
                p.getInvoiceDate() == null ? "—" : p.getInvoiceDate(), nullSafe(p.getPayeeName()));
    }

    private String nullSafe(String s) {
        return s == null ? "—" : s;
    }

    @Override
    public String name() {
        return "ScanInvoiceSkill";
    }

    @Override
    public String triggerDescription() {
        return "\"scan invoice\" (with an attached image) — runs OCR, parsing, payment, and voice confirmation";
    }

    @Override
    public int priority() {
        return 10;
    }
}
