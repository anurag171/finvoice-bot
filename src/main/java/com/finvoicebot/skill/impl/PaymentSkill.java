package com.finvoicebot.skill.impl;

import com.finvoicebot.dto.ChatResponse;
import com.finvoicebot.dto.SkillRequest;
import com.finvoicebot.exception.SkillExecutionException;
import com.finvoicebot.model.GatewayType;
import com.finvoicebot.model.InvoiceRecord;
import com.finvoicebot.model.PaymentRecord;
import com.finvoicebot.service.history.ChatHistoryService;
import com.finvoicebot.service.payment.PaymentGateway;
import com.finvoicebot.service.payment.PaymentGatewayFactory;
import com.finvoicebot.service.tts.TextToSpeechService;
import com.finvoicebot.skill.ChatSkill;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Trigger: User says "yes" / "confirm" / "proceed" after scanning an invoice.
 *
 * <p>Retrieves the recently scanned invoice and initiates payment through the configured gateway
 * (Razorpay/Stripe). This skill is separate from {@link ScanInvoiceSkill} to allow the user to
 * review the extracted fields before committing to payment.
 *
 * <p>Payment records are persisted for audit trail and later reconciliation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentSkill implements ChatSkill {

    private static final List<String> TRIGGERS = List.of("yes", "confirm", "proceed", "pay", "go ahead", "ok");
    private static final List<String> CANCEL_TRIGGERS = List.of("no", "cancel", "stop", "don't pay", "abort");

    private final PaymentGatewayFactory paymentGatewayFactory;
    private final TextToSpeechService ttsService;
    private final ChatHistoryService chatHistoryService;

    @Override
    public boolean canHandle(SkillRequest request) {
        // Only handle payment if there's a recently scanned invoice (invoiceId set) or in the session
        String normalizedMsg = request.normalizedMessage();
        
        // First check if user is explicitly declining
        if (CANCEL_TRIGGERS.stream().anyMatch(normalizedMsg::contains)) {
            return hasRecentInvoice(request);
        }
        
        // Otherwise check if they're confirming payment
        return TRIGGERS.stream().anyMatch(normalizedMsg::contains) && hasRecentInvoice(request);
    }

    @Override
    public ChatResponse handle(SkillRequest request) {
        String normalizedMsg = request.normalizedMessage();
        
        // Check for cancellation first
        if (CANCEL_TRIGGERS.stream().anyMatch(normalizedMsg::contains)) {
            return ChatResponse.builder()
                    .skillName(name())
                    .message("No problem! Payment cancelled. The invoice details remain saved in your history if you want to process it later.")
                    .success(true)
                    .build();
        }
        
        // Retrieve the invoice
        InvoiceRecord invoiceRecord = getInvoice(request);
        if (invoiceRecord == null) {
            throw new SkillExecutionException(name(), "No recent invoice found. Please scan an invoice first.");
        }

        // Check if invoice has minimum fields required for payment
        if (invoiceRecord.getAmount() == null || invoiceRecord.getAmount().compareTo(BigDecimal.ZERO) <= 0
                || invoiceRecord.getPayeeName() == null || invoiceRecord.getPayeeName().isBlank()) {
            return ChatResponse.builder()
                    .skillName(name())
                    .message("The scanned invoice doesn't have enough details for payment (missing amount or payee). "
                            + "Please scan the invoice again with a clearer image.")
                    .success(false)
                    .build();
        }

        // Initiate payment
        PaymentGateway gateway = paymentGatewayFactory.resolve(request.getMessage());
        PaymentGateway.PaymentInitiationResult paymentResult;
        try {
            paymentResult = gateway.initiatePayment(
                    com.finvoicebot.dto.ParsedInvoice.builder()
                            .invoiceNumber(invoiceRecord.getInvoiceNumber())
                            .amount(invoiceRecord.getAmount())
                            .currency(invoiceRecord.getCurrency())
                            .payeeName(invoiceRecord.getPayeeName())
                            .payeeAccountRef(invoiceRecord.getPayeeAccountRef())
                            .invoiceDate(invoiceRecord.getInvoiceDate())
                            .build());
        } catch (SkillExecutionException e) {
            // Persist failed attempt for audit
            chatHistoryService.savePayment(PaymentRecord.builder()
                    .userId(request.getUserId())
                    .invoiceRecordId(invoiceRecord.getId())
                    .gateway(gateway.type())
                    .gatewayReferenceId("FAILED")
                    .amount(invoiceRecord.getAmount())
                    .currency(invoiceRecord.getCurrency())
                    .status("FAILED")
                    .gatewayResponseJson(e.getMessage())
                    .build());
            throw e;
        }

        // Persist successful payment attempt
        PaymentRecord paymentRecord = chatHistoryService.savePayment(PaymentRecord.builder()
                .userId(request.getUserId())
                .invoiceRecordId(invoiceRecord.getId())
                .gateway(gateway.type())
                .gatewayReferenceId(paymentResult.gatewayReferenceId())
                .amount(invoiceRecord.getAmount())
                .currency(invoiceRecord.getCurrency())
                .status(paymentResult.status())
                .gatewayResponseJson(paymentResult.rawResponseJson())
                .build());

        // Attempt TTS confirmation (non-blocking — don't fail if audio generation fails)
        String confirmationText = buildConfirmationSpeech(invoiceRecord, gateway.type(), paymentResult.status());
        String audioUrl = null;
        try {
            audioUrl = ttsService.synthesizeToFile(confirmationText);
        } catch (SkillExecutionException e) {
            log.warn("TTS synthesis failed after successful payment {}: {}", paymentRecord.getId(), e.getMessage());
        }

        String userMessage = """
✓ Payment processed!

Invoice #%s for %s %s to %s
Gateway: %s
Reference: %s
Status: %s""".formatted(
        invoiceRecord.getInvoiceNumber(),
        invoiceRecord.getCurrency(),
        formatAmount(invoiceRecord.getAmount()),
        invoiceRecord.getPayeeName(),
        gateway.type(),
        paymentResult.gatewayReferenceId(),
        paymentResult.status()
);

        return ChatResponse.builder()
                .skillName(name())
                .message(userMessage)
                .audioUrl(audioUrl)
                .success(true)
                .build();
    }

    private InvoiceRecord getInvoice(SkillRequest request) {
        // First try to use the invoiceId from the request (most recent scan)
        if (request.getInvoiceId() != null) {
            Optional<InvoiceRecord> invoiceRecord = chatHistoryService.getInvoiceById(request.getInvoiceId());
            if (invoiceRecord.isPresent()) {
                return invoiceRecord.get();
            }
        }
        
        // Fallback: get the most recent invoice from the session
        Optional<InvoiceRecord> invoiceRecord = chatHistoryService.lastInvoiceForSession(request.getSessionId());
        return invoiceRecord.orElse(null);
    }

    private boolean hasRecentInvoice(SkillRequest request) {
        if (request.getInvoiceId() != null) {
            return chatHistoryService.getInvoiceById(request.getInvoiceId()).isPresent();
        }
        return chatHistoryService.lastInvoiceForSession(request.getSessionId()).isPresent();
    }

    private String buildConfirmationSpeech(InvoiceRecord invoice, GatewayType gateway, String status) {
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

    @Override
    public String name() {
        return "PaymentSkill";
    }

    @Override
    public String triggerDescription() {
        return "\"yes\" / \"confirm\" (after scanning an invoice) — initiates payment to the extracted payee";
    }

    @Override
    public int priority() {
        return 15; // Higher priority than StatusSkill to intercept yes/no responses before help
    }
}
