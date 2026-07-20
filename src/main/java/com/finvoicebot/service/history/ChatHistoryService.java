package com.finvoicebot.service.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finvoicebot.dto.ChatResponse;
import com.finvoicebot.dto.ParsedInvoice;
import com.finvoicebot.dto.SkillRequest;
import com.finvoicebot.model.ChatMessage;
import com.finvoicebot.model.InvoiceRecord;
import com.finvoicebot.model.PaymentRecord;
import com.finvoicebot.repository.ChatMessageRepository;
import com.finvoicebot.repository.InvoiceRepository;
import com.finvoicebot.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Central persistence point for the audit trail: every user turn, every bot response
 * (with its structured data and any audio clip), every parsed invoice, and every gateway
 * response. Skills call this rather than repositories directly, keeping the audit-log
 * shape consistent across all six skills.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatHistoryService {

    private final ChatMessageRepository chatMessageRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void recordUserTurn(SkillRequest request) {
        chatMessageRepository.save(ChatMessage.builder()
                .userId(request.getUserId())
                .sessionId(request.getSessionId())
                .fromUser(true)
                .content(request.getMessage() == null ? "[invoice image uploaded]" : request.getMessage())
                .build());
    }

    @Transactional
    public void recordBotTurn(SkillRequest request, ChatResponse response) {
        String dataJson = null;
        if (response.getData() != null) {
            try {
                dataJson = objectMapper.writeValueAsString(response.getData());
            } catch (Exception e) {
                log.warn("Could not serialize response data for audit log", e);
            }
        }
        chatMessageRepository.save(ChatMessage.builder()
                .userId(request.getUserId())
                .sessionId(request.getSessionId())
                .fromUser(false)
                .content(response.getMessage())
                .skillName(response.getSkillName())
                .dataJson(dataJson)
                .audioUrl(response.getAudioUrl())
                .build());
    }

    @Transactional
    public InvoiceRecord saveInvoice(SkillRequest request, ParsedInvoice parsed, String sourceImageName) {
        InvoiceRecord invoiceRecord = InvoiceRecord.builder()
                .userId(request.getUserId())
                .sessionId(request.getSessionId())
                .invoiceNumber(parsed.getInvoiceNumber())
                .amount(parsed.getAmount())
                .currency(parsed.getCurrency())
                .invoiceDate(parsed.getInvoiceDate())
                .payeeName(parsed.getPayeeName())
                .payeeAccountRef(parsed.getPayeeAccountRef())
                .parseConfidence(parsed.getParseConfidence())
                .sourceImageName(sourceImageName)
                .rawOcrText(parsed.getRawOcrText())
                .build();
        return invoiceRepository.save(invoiceRecord);
    }

    @Transactional
    public PaymentRecord savePayment(PaymentRecord paymentRecord) {
        return paymentRepository.save(paymentRecord);
    }

    public List<String> listSessionsForUser(String userId) {
        return chatMessageRepository.findDistinctSessionIdsByUserId(userId);
    }

    public List<ChatMessage> getSessionHistory(String sessionId) {
        return chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    public List<InvoiceRecord> listInvoicesForUser(String userId) {
        return invoiceRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public Optional<InvoiceRecord> lastInvoiceForSession(String sessionId) {
        return invoiceRepository.findFirstBySessionIdOrderByCreatedAtDesc(sessionId);
    }

    public Optional<InvoiceRecord> lastInvoiceForUser(String userId) {
        return invoiceRepository.findFirstByUserIdOrderByCreatedAtDesc(userId);
    }

    public Optional<PaymentRecord> lastPaymentForInvoice(Long invoiceId) {
        return paymentRepository.findFirstByInvoiceRecordIdOrderByCreatedAtDesc(invoiceId);
    }

    public Optional<PaymentRecord> lastPaymentForUser(String userId) {
        return paymentRepository.findFirstByUserIdOrderByCreatedAtDesc(userId);
    }

    public Optional<PaymentRecord> findPaymentByReference(String gatewayReferenceId) {
        return paymentRepository.findByGatewayReferenceId(gatewayReferenceId);
    }

    public Optional<InvoiceRecord> getInvoiceById(Long invoiceId) {
        return invoiceRepository.findById(invoiceId);
    }
}
