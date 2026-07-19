package com.finvoicebot.service.payment;

import com.finvoicebot.exception.SkillExecutionException;
import com.finvoicebot.model.GatewayType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runtime gateway selector. Both Razorpay and Stripe beans are registered; this factory picks
 * one per request based on:
 * <ol>
 *   <li>an explicit gateway named in the request (e.g. "scan invoice via stripe")</li>
 *   <li>the configured default ({@code finvoice.payment.default-gateway})</li>
 * </ol>
 * Adding a third provider means adding one more {@link PaymentGateway} bean — no changes here
 * beyond the currency/name mapping.
 */
@Component
public class PaymentGatewayFactory {

    private final Map<GatewayType, PaymentGateway> gatewaysByType;
    private final GatewayType defaultGateway;

    public PaymentGatewayFactory(List<PaymentGateway> gateways,
                                  @Value("${finvoice.payment.default-gateway:RAZORPAY}") String defaultGatewayName) {
        this.gatewaysByType = gateways.stream()
                .collect(Collectors.toMap(PaymentGateway::type, Function.identity()));
        this.defaultGateway = GatewayType.valueOf(defaultGatewayName.trim().toUpperCase());
    }

    public PaymentGateway resolve(String userMessage) {
        GatewayType requested = detectRequestedGateway(userMessage);
        GatewayType target = requested != null ? requested : defaultGateway;
        PaymentGateway gateway = gatewaysByType.get(target);
        if (gateway == null) {
            throw new SkillExecutionException("PaymentSkill", "No payment gateway registered for " + target);
        }
        return gateway;
    }

    public PaymentGateway byType(GatewayType type) {
        PaymentGateway gateway = gatewaysByType.get(type);
        if (gateway == null) {
            throw new SkillExecutionException("PaymentSkill", "No payment gateway registered for " + type);
        }
        return gateway;
    }

    private GatewayType detectRequestedGateway(String userMessage) {
        if (userMessage == null) return null;
        String lower = userMessage.toLowerCase();
        if (lower.contains("razorpay")) return GatewayType.RAZORPAY;
        if (lower.contains("stripe")) return GatewayType.STRIPE;
        return null;
    }
}
