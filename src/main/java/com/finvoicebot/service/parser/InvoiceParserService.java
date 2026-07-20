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
            Pattern.compile("inv\\s*[:\\-#]\\s*([A-Za-z0-9\\-/]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("cheque?\\s*(?:no\\.?|number|#)\\s*[:\\-]?\\s*([A-Za-z0-9\\-/]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("chq\\s*[:\\-#]\\s*([A-Za-z0-9\\-/]+)", Pattern.CASE_INSENSITIVE),
            // Bare cheque number (7-10 digits, typically appears at start of line or after specific markers)
            Pattern.compile("^([0-9]{6,10})$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE)
    );

    private static final List<Pattern> AMOUNT_PATTERNS = List.of(
            // Amount with explicit currency and rupee lakh format (₹50,25,000/-)
            Pattern.compile("([₹$€£])\\s*([0-9][0-9,]*(?:[0-9]{2})?)\\s*[-/]?", Pattern.CASE_INSENSITIVE),
            // Generic pattern with keywords and optional currency
            Pattern.compile("(?:total\\s*(?:due|amount)?|amount\\s*due|grand\\s*total|balance\\s*due)\\s*[:\\-]?\\s*"
                    + "([₹$€£]?)\\s*([0-9][0-9,]*\\.?[0-9]{0,2})", Pattern.CASE_INSENSITIVE),
            // Bare amount with rupee format - no explicit currency, capture empty string for group 1
            Pattern.compile("()([0-9]{1,2},?[0-9]{2},[0-9]{3}(?:,-)?(?:/-)?)\\b", Pattern.CASE_INSENSITIVE)
    );

    private static final List<Pattern> DATE_PATTERNS = List.of(
            Pattern.compile("(?:invoice\\s*date|date)\\s*[:\\-]?\\s*"
                    + "(\\d{1,2}[/\\-]\\d{1,2}[/\\-]\\d{2,4}|\\d{4}[/\\-]\\d{1,2}[/\\-]\\d{1,2}|"
                    + "[A-Za-z]{3,9}\\s+\\d{1,2},?\\s+\\d{4})", Pattern.CASE_INSENSITIVE),
            // Bare date patterns for cheques (8-digit date, slash or dash separated)
            Pattern.compile("(\\d{2}[/\\-]\\d{2}[/\\-]\\d{4}|\\d{4}[/\\-]\\d{2}[/\\-]\\d{2})", Pattern.CASE_INSENSITIVE),
            // DDMMYY format
            Pattern.compile("(\\d{2}\\d{2}\\d{2})", Pattern.CASE_INSENSITIVE)
    );

    private static final List<Pattern> PAYEE_PATTERNS = List.of(
            // Explicit "PAY TO" label
            Pattern.compile("(?:pay\\s*to|payee|vendor|bill\\s*from|remit\\s*to)\\s*[:\\-]?\\s*([A-Za-z0-9&.,'\\- ]{3,60})",
                    Pattern.CASE_INSENSITIVE),
            // Standalone "PAY" label followed by name
            Pattern.compile("^\\s*pay\\s+([A-Za-z0-9&.,'\\- ]{3,60})", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
            // Standalone proper name (ALL CAPS or Title Case, 3+ chars, appears before account/amount info)
            // Look for name after "Please sign" or just before account lines
            Pattern.compile("([A-Z][A-Za-z]{2,}(?:\\s+[A-Z][A-Za-z]+)*?)(?:\\n|\\s*(?:SAVINGS|ACCOUNT|A/C|Please\\s+sign|Quak))", 
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE)
    );

    private static final List<Pattern> ACCOUNT_REF_PATTERNS = List.of(
            Pattern.compile("(?:account\\s*(?:no\\.?|number)|iban|a/c\\s*no\\.?)\\s*[:\\-]?\\s*([A-Za-z0-9\\-]{4,34})",
                    Pattern.CASE_INSENSITIVE),
            // Bare account number (multiple digits, often seen after "A/C" label)
            Pattern.compile("a/c\\s*[:\\-]?\\s*([0-9]{4,34})", Pattern.CASE_INSENSITIVE),
            // SAVINGS AC or similar followed by number
            Pattern.compile("(?:savings\\s+a/c|account)\\s+([0-9]{4,34})", Pattern.CASE_INSENSITIVE),
            // Bare 10-digit account number (typically after SAVINGS A/C label)
            Pattern.compile("^([0-9]{10,34})$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE)
    );

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("d-M-yyyy"),
            DateTimeFormatter.ofPattern("M/d/yyyy"),
            DateTimeFormatter.ofPattern("yyyy-M-d"),
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("MMMM d, yyyy"),
            DateTimeFormatter.ofPattern("MMM d, yyyy"),
            DateTimeFormatter.ofPattern("d MMMM yyyy"),
            DateTimeFormatter.ofPattern("dd MMM yyyy")
    );

    public ParsedInvoice parse(String rawOcrText) {
        if (rawOcrText == null || rawOcrText.isBlank()) {
            return ParsedInvoice.builder().parseConfidence(0.0).rawOcrText(rawOcrText).build();
        }

        log.debug("Raw OCR text for parsing:\n{}", rawOcrText);

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
            if (m.find()) {
                if (m.groupCount() >= group) {
                    String value = m.group(group);
                    if (value != null && !value.isBlank()) {
                        log.debug("Pattern match: {} => group({}): '{}'", p.pattern(), group, value);
                        return value.trim();
                    }
                } else {
                    log.debug("Pattern matched but group {} not available. groupCount={}, pattern: {}", 
                            group, m.groupCount(), p.pattern());
                }
            }
        }
        log.debug("No pattern matched for group {}", group);
        return null;
    }

    private BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            // Remove all commas and currency symbols, keep only digits and decimal
            String cleaned = raw.replaceAll("[,₹$€£\\s/\\-]", "").trim();
            if (cleaned.isEmpty()) return null;
            return new BigDecimal(cleaned);
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
