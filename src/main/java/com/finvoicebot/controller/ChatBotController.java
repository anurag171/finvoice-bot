package com.finvoicebot.controller;

import com.finvoicebot.dto.ChatResponse;
import com.finvoicebot.dto.SkillRequest;
import com.finvoicebot.exception.SkillExecutionException;
import com.finvoicebot.model.ChatMessage;
import com.finvoicebot.model.InvoiceRecord;
import com.finvoicebot.service.history.ChatHistoryService;
import com.finvoicebot.strategy.AgentSkillStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Single entry point for every user message. All routing/orchestration logic lives in
 * {@link AgentSkillStrategy} and the individual skills — this controller's only job is to
 * build a {@link SkillRequest} from the HTTP request, persist the user's turn, delegate,
 * persist the bot's turn, and translate exceptions into a clean JSON error response.
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatBotController {

    private final AgentSkillStrategy agentSkillStrategy;
    private final ChatHistoryService chatHistoryService;

    /**
     * Main chat endpoint. Accepts multipart form data so a message and an optional invoice
     * image can be submitted together in one request (what "scan invoice" needs).
     */
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<ChatResponse> sendMessage(
            @RequestParam("userId") String userId,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "message", required = false) String message,
            @RequestParam(value = "invoiceImage", required = false) MultipartFile invoiceImage,
            @RequestParam(value = "invoiceId", required = false) Long invoiceId) {

        String resolvedSessionId = (sessionId == null || sessionId.isBlank())
                ? UUID.randomUUID().toString()
                : sessionId;

        byte[] imageBytes = null;
        String imageFilename = null;
        try {
            if (invoiceImage != null && !invoiceImage.isEmpty()) {
                imageBytes = invoiceImage.getBytes();
                imageFilename = invoiceImage.getOriginalFilename();
            }
        } catch (IOException e) {
            log.error("Failed to read uploaded invoice image", e);
            return ResponseEntity.badRequest().body(ChatResponse.error("ChatBotController", "Could not read the uploaded image."));
        }

        SkillRequest request = SkillRequest.builder()
                .userId(userId)
                .sessionId(resolvedSessionId)
                .message(message)
                .invoiceImage(imageBytes)
                .imageFilename(imageFilename)
                .invoiceId(invoiceId)
                .build();

        chatHistoryService.recordUserTurn(request);

        try {
            ChatResponse response = agentSkillStrategy.route(request);
            chatHistoryService.recordBotTurn(request, response);
            return ResponseEntity.ok(response);
        } catch (SkillExecutionException e) {
            log.warn("Skill execution failed: {}", e.getMessage());
            ChatResponse errorResponse = ChatResponse.error(e.getSkillName(), e.getMessage());
            chatHistoryService.recordBotTurn(request, errorResponse);
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(errorResponse);
        } catch (Exception e) {
            log.error("Unexpected error handling chat message", e);
            ChatResponse errorResponse = ChatResponse.error("ChatBotController", "Something went wrong on our end. Please try again.");
            chatHistoryService.recordBotTurn(request, errorResponse);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /** Sidebar: list of past session ids for a user, most recent first. */
    @GetMapping("/sessions")
    public ResponseEntity<List<String>> listSessions(@RequestParam("userId") String userId) {
        return ResponseEntity.ok(chatHistoryService.listSessionsForUser(userId));
    }

    /** Full transcript of a single session, in order. */
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<List<ChatMessage>> getSession(@PathVariable String sessionId) {
        return ResponseEntity.ok(chatHistoryService.getSessionHistory(sessionId));
    }

    /** Sidebar: all invoices scanned by a user, most recent first. */
    @GetMapping("/invoices")
    public ResponseEntity<List<InvoiceRecord>> listInvoices(@RequestParam("userId") String userId) {
        return ResponseEntity.ok(chatHistoryService.listInvoicesForUser(userId));
    }
}
