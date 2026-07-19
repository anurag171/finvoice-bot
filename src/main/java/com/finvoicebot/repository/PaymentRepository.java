package com.finvoicebot.repository;

import com.finvoicebot.model.PaymentRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<PaymentRecord, Long> {

    List<PaymentRecord> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<PaymentRecord> findFirstByInvoiceRecordIdOrderByCreatedAtDesc(Long invoiceRecordId);

    Optional<PaymentRecord> findByGatewayReferenceId(String gatewayReferenceId);

    Optional<PaymentRecord> findFirstByUserIdOrderByCreatedAtDesc(String userId);
}
