package com.finvoicebot.service.payment;

import com.finvoicebot.dto.ParsedInvoice;
import com.finvoicebot.exception.SkillExecutionException;
import com.finvoicebot.model.GatewayType;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Stripe implementation, using PaymentIntents. Amounts are sent in the smallest currency unit
 * (cents for USD), matching Stripe's API contract.
 *
 * Requires the STRIPE_SECRET_KEY env var (see application.yml) — never hardcode this;
 * source it from Vault/Secret Manager in any non-local environment.
 */
@Slf4j
@Component
public class StripeGateway implements PaymentGateway {

    private final String secretKey;

    public StripeGateway(@Value("${finvoice.payment.stripe.secret-key:}") String secretKey) {
        this.secretKey = secretKey;
    }

    @Override
    public GatewayType type() {
        return GatewayType.STRIPE;
    }

    @Override
    public PaymentInitiationResult initiatePayment(ParsedInvoice invoice) {
        requireCredentials();
        Stripe.apiKey = secretKey;
        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(toSubunits(invoice.getAmount()))
                    .setCurrency(normalizeCurrency(invoice.getCurrency()))
                    .setDescription("Invoice " + invoice.getInvoiceNumber() + " - " + invoice.getPayeeName())
                    .putMetadata("invoice_number", String.valueOf(invoice.getInvoiceNumber()))
                    .putMetadata("payee", String.valueOf(invoice.getPayeeName()))
                    .build();

            PaymentIntent intent = PaymentIntent.create(params);
            log.info("Stripe PaymentIntent created: {} status={}", intent.getId(), intent.getStatus());
            return new PaymentInitiationResult(intent.getId(), intent.getStatus(), intent.toJson());

        } catch (StripeException e) {
            log.error("Stripe PaymentIntent creation failed", e);
            throw new SkillExecutionException("PaymentSkill", "Stripe payment could not be initiated: " + e.getMessage(), e);
        }
    }

    @Override
    public PaymentStatusResult checkStatus(String gatewayReferenceId) {
        requireCredentials();
        Stripe.apiKey = secretKey;
        try {
            PaymentIntent intent = PaymentIntent.retrieve(gatewayReferenceId);
            return new PaymentStatusResult(intent.getStatus(), intent.toJson());
        } catch (StripeException e) {
            log.error("Stripe status check failed for {}", gatewayReferenceId, e);
            throw new SkillExecutionException("StatusSkill", "Could not fetch Stripe status: " + e.getMessage(), e);
        }
    }

    private long toSubunits(BigDecimal amount) {
        if (amount == null) {
            throw new SkillExecutionException("PaymentSkill", "Invoice amount is missing — cannot initiate payment.");
        }
        return amount.multiply(BigDecimal.valueOf(100)).longValueExact();
    }

    private String normalizeCurrency(String currency) {
        return ((currency == null || currency.isBlank()) ? "usd" : currency).toLowerCase();
    }

    private void requireCredentials() {
        if (secretKey == null || secretKey.isBlank()) {
            throw new SkillExecutionException("PaymentSkill", "Stripe is not configured — set STRIPE_SECRET_KEY.");
        }
    }
}
