package com.finvoicebot.skill.impl;

import com.finvoicebot.dto.ChatResponse;
import com.finvoicebot.dto.ParsedInvoice;
import com.finvoicebot.dto.SkillRequest;
import com.finvoicebot.model.InvoiceRecord;
import com.finvoicebot.service.history.ChatHistoryService;
import com.finvoicebot.service.ocr.OcrService;
import com.finvoicebot.service.parser.InvoiceParserService;
import com.finvoicebot.skill.ChatSkill;

import io.micrometer.common.lang.NonNull;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * Trigger: "scan invoice" (with an attached invoice image).
 *
 * <p>Scans and parses the invoice image, then asks the user if they want to proceed with payment.
 * Does NOT perform payment — payment is handled separately by {@link PaymentSkill}.
 *
 * <p>The invoice is persisted via {@link ChatHistoryService} for audit trail and later payment.
 */
@Slf4j
@Component
@AllArgsConstructor
public class ScanInvoiceSkill implements ChatSkill {

    private static final List<String> TRIGGERS = List.of("scan invoice", "scan the invoice", "process invoice");
    
    @NonNull
    private final OcrService ocrService;
    @NonNull
    private final InvoiceParserService parserService;
    @NonNull
    private final ChatHistoryService chatHistoryService;

    @Override
    public boolean canHandle(SkillRequest request) {
        if (!request.hasImage()) {
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

        // 3) Present the scanned fields to the user — ready for review and payment decision
        String msg = """
✓ Invoice scanned with %.0f%% confidence. Here's what I extracted:

📄 Invoice #: %s
💰 Amount: %s %s
📅 Date: %s
👤 Payee: %s
🏦 Account: %s

Would you like me to proceed with payment? (Say "yes" or "no")""".formatted(
        parsed.getParseConfidence() * 100,
        nullSafe(parsed.getInvoiceNumber()),
        nullSafe(parsed.getCurrency()),
        parsed.getAmount() == null ? "—" : formatAmount(parsed.getAmount()),
        parsed.getInvoiceDate() == null ? "—" : parsed.getInvoiceDate(),
        nullSafe(parsed.getPayeeName()),
        nullSafe(parsed.getPayeeAccountRef())
);

        return ChatResponse.builder()
                .skillName(name())
                .message(msg)
                .data(parsed)
                .invoiceId(invoiceRecord.getId())
                .success(true)
                .build();
    }

    private String formatAmount(java.math.BigDecimal amount) {
        if (amount == null) return "unknown";
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return nf.format(amount);
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
