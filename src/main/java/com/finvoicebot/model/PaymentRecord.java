package com.finvoicebot.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payment_record", indexes = {
        @Index(name = "idx_payment_user", columnList = "userId"),
        @Index(name = "idx_payment_invoice", columnList = "invoiceRecordId")
})
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PaymentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    private Long invoiceRecordId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GatewayType gateway;

    /** Gateway-issued order/payment intent id — used by the Status skill to poll. */
    @Column(nullable = false)
    private String gatewayReferenceId;

    private BigDecimal amount;

    private String currency;

    @Column(nullable = false)
    private String status;

    /** Raw gateway response body, kept verbatim for audit. */
    @Lob
    private String gatewayResponseJson;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant lastCheckedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
