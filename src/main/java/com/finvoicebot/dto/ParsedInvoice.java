package com.finvoicebot.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Structured invoice fields extracted from raw OCR text by the Parser skill.
 * This is the object persisted to {@code invoice_record} and handed to the payment skill.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ParsedInvoice {
    private String invoiceNumber;
    private BigDecimal amount;
    private String currency;
    private LocalDate invoiceDate;
    private String payeeName;
    private String payeeAccountRef;
    /** Confidence score 0-1 for how much of the schema was successfully matched. */
    private double parseConfidence;
    /** Raw OCR text this was derived from, kept for audit/debugging. */
    private String rawOcrText;

    public boolean hasMinimumFieldsForPayment() {
        return invoiceNumber != null && amount != null && amount.compareTo(BigDecimal.ZERO) > 0 && payeeName != null;
    }
}
