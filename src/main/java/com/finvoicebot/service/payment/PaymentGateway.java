package com.finvoicebot.service.payment;

import com.finvoicebot.dto.ParsedInvoice;
import com.finvoicebot.model.GatewayType;

/**
 * Uniform contract for triggering and checking a payment, regardless of the underlying
 * provider (Razorpay, Stripe, ...). The Payment skill depends only on this interface,
 * selected at runtime by {@link PaymentGatewayFactory} — new providers can be added
 * without touching skill/routing code.
 */
public interface PaymentGateway {

    GatewayType type();

    /**
     * Creates a payment order/intent for the parsed invoice.
     * @return the gateway's own reference id (Razorpay order id / Stripe PaymentIntent id) plus raw JSON.
     */
    PaymentInitiationResult initiatePayment(ParsedInvoice invoice);

    /**
     * Polls the gateway for the current status of a previously created payment.
     */
    PaymentStatusResult checkStatus(String gatewayReferenceId);

    /**
     * Simple holder for the result of {@link #initiatePayment}.
     */
    record PaymentInitiationResult(String gatewayReferenceId, String status, String rawResponseJson) {}

    /**
     * Simple holder for the result of {@link #checkStatus}.
     */
    record PaymentStatusResult(String status, String rawResponseJson) {}
}
