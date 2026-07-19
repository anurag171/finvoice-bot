package com.finvoicebot.service.payment;

import com.finvoicebot.dto.ParsedInvoice;
import com.finvoicebot.exception.SkillExecutionException;
import com.finvoicebot.model.GatewayType;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Razorpay implementation. Amounts are sent in the smallest currency unit (paise for INR),
 * matching Razorpay's API contract.
 *
 * Requires RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET env vars (see application.yml) — never hardcode
 * these; source them from Vault/Secret Manager in any non-local environment.
 */
@Slf4j
@Component
public class RazorpayGateway implements PaymentGateway {

    private final String keyId;
    private final String keySecret;

    public RazorpayGateway(
            @Value("${finvoice.payment.razorpay.key-id:}") String keyId,
            @Value("${finvoice.payment.razorpay.key-secret:}") String keySecret) {
        this.keyId = keyId;
        this.keySecret = keySecret;
    }

    @Override
    public GatewayType type() {
        return GatewayType.RAZORPAY;
    }

    @Override
    public PaymentInitiationResult initiatePayment(ParsedInvoice invoice) {
        requireCredentials();
        try {
            RazorpayClient client = new RazorpayClient(keyId, keySecret);

            long amountInSubunits = toSubunits(invoice.getAmount());
            JSONObject orderRequest = new JSONObject();
            orderRequest.put("amount", amountInSubunits);
            orderRequest.put("currency", normalizeCurrency(invoice.getCurrency()));
            orderRequest.put("receipt", invoice.getInvoiceNumber());
            JSONObject notes = new JSONObject();
            notes.put("payee", invoice.getPayeeName());
            notes.put("invoice_number", invoice.getInvoiceNumber());
            orderRequest.put("notes", notes);

            Order order = client.orders.create(orderRequest);
            String rawJson = order.toString();
            String status = order.get("status");
            log.info("Razorpay order created: {} status={}", order.get("id").toString(), status);
            return new PaymentInitiationResult(order.get("id").toString(), status, rawJson);

        } catch (RazorpayException e) {
            log.error("Razorpay order creation failed", e);
            throw new SkillExecutionException("PaymentSkill", "Razorpay payment could not be initiated: " + e.getMessage(), e);
        }
    }

    @Override
    public PaymentStatusResult checkStatus(String gatewayReferenceId) {
        requireCredentials();
        try {
            RazorpayClient client = new RazorpayClient(keyId, keySecret);
            Order order = client.orders.fetch(gatewayReferenceId);
            return new PaymentStatusResult(order.get("status"), order.toString());
        } catch (RazorpayException e) {
            log.error("Razorpay status check failed for {}", gatewayReferenceId, e);
            throw new SkillExecutionException("StatusSkill", "Could not fetch Razorpay status: " + e.getMessage(), e);
        }
    }

    private long toSubunits(BigDecimal amount) {
        if (amount == null) {
            throw new SkillExecutionException("PaymentSkill", "Invoice amount is missing — cannot initiate payment.");
        }
        return amount.multiply(BigDecimal.valueOf(100)).longValueExact();
    }

    private String normalizeCurrency(String currency) {
        return (currency == null || currency.isBlank()) ? "INR" : currency.toUpperCase();
    }

    private void requireCredentials() {
        if (keyId == null || keyId.isBlank() || keySecret == null || keySecret.isBlank()) {
            throw new SkillExecutionException("PaymentSkill",
                    "Razorpay is not configured — set RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET.");
        }
    }
}
