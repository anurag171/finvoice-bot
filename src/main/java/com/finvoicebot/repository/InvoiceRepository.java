package com.finvoicebot.repository;

import com.finvoicebot.model.InvoiceRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<InvoiceRecord, Long> {

    List<InvoiceRecord> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<InvoiceRecord> findFirstBySessionIdOrderByCreatedAtDesc(String sessionId);

    Optional<InvoiceRecord> findFirstByUserIdOrderByCreatedAtDesc(String userId);
}
