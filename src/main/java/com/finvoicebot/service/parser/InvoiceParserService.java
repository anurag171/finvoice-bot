package com.finvoicebot.service.parser;

import com.finvoicebot.dto.ParsedInvoice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts the raw text block returned by {@link com.finvoicebot.service.ocr.OcrService} into a
 * structured {@link ParsedInvoice}.
 *
 * <p>This uses a layered, deterministic regex/heuristic strategy rather than an LLM call, which
 * keeps per-invoice cost at zero beyond the Vision API charge and keeps behavior auditable and
 * reproducible — a requirement in the payments domain. Swap in an LLM-based extractor behind the
 * same interface if messier, non-templated invoice layouts start reducing match quality.
 */
@Slf4j
@Service
public class InvoiceParserService {

    private static final List<Pattern> INVOICE_NUMBER_PATTERNS = List.of(
            Pattern.compile("invoice\\s*(?:no\\.?|number|#)\\s*[:\\-]?\\s*([A-Za-z0-9\\-/]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("inv\\s*[:\\-#]\\s*([A-Za-z0-9\\-/]+)", Pattern.CASE_INSENSITIVE)
    );

    private static final List<Pattern> AMOUNT_PATTERNS = List.of(
            Pattern.compile("(?:total\\s*(?:due|amount)?|amount\\s*due|grand\\s*total|balance\\s*due)\\s*[:\\-]?\\s*"
                    + "([₹$€£]?)\\s*([0-9][0-9,]*\\.?[0-9]{0,2})", Pattern.CASE_INSENSITIVE)
    );

    private static final List<Pattern> DATE_PATTERNS = List.of(
            Pattern.compile("(?:invoice\\s*date|date)\\s*[:\\-]?\\s*"
                    + "(\\d{1,2}[/\\-]\\d{1,2}[/\\-]\\d{2,4}|\\d{4}[/\\-]\\d{1,2}[/\\-]\\d{1,2}|"
                    + "[A-Za-z]{3,9}\\s+\\d{1,2},?\\s+\\d{4})", Pattern.CASE_INSENSITIVE)
    );

    private static final List<Pattern> PAYEE_PATTERNS = List.of(
            Pattern.compile("(?:pay\\s*to|payee|vendor|bill\\s*from|remit\\s*to)\\s*[:\\-]?\\s*([A-Za-z0-9&.,'\\- ]{3,60})",
                    Pattern.CASE_INSENSITIVE)
    );

    private static final List<Pattern> ACCOUNT_REF_PATTERNS = List.of(
            Pattern.compile("(?:account\\s*(?:no\\.?|number)|iban|a/c\\s*no\\.?)\\s*[:\\-]?\\s*([A-Za-z0-9\\-]{4,34})",
                    Pattern.CASE_INSENSITIVE)
    );

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("d-M-yyyy"),
            DateTimeFormatter.ofPattern("M/d/yyyy"),
            DateTimeFormatter.ofPattern("yyyy-M-d"),
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("MMMM d, yyyy"),
            DateTimeFormatter.ofPattern("MMM d, yyyy")
    );

    public ParsedInvoice parse(String rawOcrText) {
        if (rawOcrText == null || rawOcrText.isBlank()) {
            return ParsedInvoice.builder().parseConfidence(0.0).rawOcrText(rawOcrText).build();
        }

        String invoiceNumber = firstMatch(INVOICE_NUMBER_PATTERNS, rawOcrText);
        String amountRaw = firstMatch(AMOUNT_PATTERNS, rawOcrText, 2);
        String currencySymbol = firstMatch(AMOUNT_PATTERNS, rawOcrText, 1);
        String dateRaw = firstMatch(DATE_PATTERNS, rawOcrText);
        String payee = firstMatch(PAYEE_PATTERNS, rawOcrText);
        String accountRef = firstMatch(ACCOUNT_REF_PATTERNS, rawOcrText);

        BigDecimal amount = parseAmount(amountRaw);
        LocalDate date = parseDate(dateRaw);
        String currency = mapCurrencySymbol(currencySymbol);

        int fieldsFound = 0;
        int totalFields = 5;
        if (invoiceNumber != null) fieldsFound++;
        if (amount != null) fieldsFound++;
        if (date != null) fieldsFound++;
        if (payee != null) fieldsFound++;
        if (accountRef != null) fieldsFound++;
        double confidence = (double) fieldsFound / totalFields;

        ParsedInvoice invoice = ParsedInvoice.builder()
                .invoiceNumber(invoiceNumber)
                .amount(amount)
                .currency(currency)
                .invoiceDate(date)
                .payeeName(payee == null ? null : payee.trim())
                .payeeAccountRef(accountRef)
                .parseConfidence(confidence)
                .rawOcrText(rawOcrText)
                .build();

        log.info("Parsed invoice with confidence {} (fields found: {}/{})", confidence, fieldsFound, totalFields);
        return invoice;
    }

    private String firstMatch(List<Pattern> patterns, String text) {
        return firstMatch(patterns, text, 1);
    }

    private String firstMatch(List<Pattern> patterns, String text, int group) {
        for (Pattern p : patterns) {
            Matcher m = p.matcher(text);
            if (m.find() && m.groupCount() >= group) {
                String value = m.group(group);
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    private BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return new BigDecimal(raw.replace(",", ""));
        } catch (NumberFormatException e) {
            log.warn("Could not parse amount from OCR text fragment: '{}'", raw);
            return null;
        }
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return LocalDate.parse(raw.replace(",", ""), fmt);
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }
        log.warn("Could not parse date from OCR text fragment: '{}'", raw);
        return null;
    }

    private String mapCurrencySymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return "INR"; // sensible default for this org's context
        return switch (symbol) {
            case "$" -> "USD";
            case "€" -> "EUR";
            case "£" -> "GBP";
            case "₹" -> "INR";
            default -> "INR";
        };
    }
}
