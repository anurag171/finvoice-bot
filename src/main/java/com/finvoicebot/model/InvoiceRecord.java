package com.finvoicebot.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "invoice_record", indexes = {
        @Index(name = "idx_invoice_user", columnList = "userId"),
        @Index(name = "idx_invoice_session", columnList = "sessionId")
})
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class InvoiceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String sessionId;

    private String invoiceNumber;

    private BigDecimal amount;

    private String currency;

    private LocalDate invoiceDate;

    private String payeeName;

    private String payeeAccountRef;

    private double parseConfidence;

    /** Original filename of the uploaded invoice image, for traceability. */
    private String sourceImageName;

    @Lob
    private String rawOcrText;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
