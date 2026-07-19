package com.finvoicebot.skill;

import com.finvoicebot.dto.SkillRequest;
import com.finvoicebot.service.history.ChatHistoryService;
import com.finvoicebot.service.ocr.OcrService;
import com.finvoicebot.service.parser.InvoiceParserService;
import com.finvoicebot.service.payment.PaymentGatewayFactory;
import com.finvoicebot.service.tts.TextToSpeechService;
import com.finvoicebot.skill.impl.ScanInvoiceSkill;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ScanInvoiceSkillTest {

    private ScanInvoiceSkill skill;

    @BeforeEach
    void setUp() {
        skill = new ScanInvoiceSkill(
                mock(OcrService.class),
                mock(InvoiceParserService.class),
                mock(PaymentGatewayFactory.class),
                mock(TextToSpeechService.class),
                mock(ChatHistoryService.class),
                new ObjectMapper());
    }

    @Test
    void doesNotMatchWithoutImageEvenIfTriggerPhrasePresent() {
        SkillRequest request = SkillRequest.builder().userId("u").sessionId("s").message("scan invoice").build();
        assertFalse(skill.canHandle(request), "Should not claim the message without an attached image");
    }

    @Test
    void doesNotMatchImageAloneWithoutTriggerPhrase() {
        SkillRequest request = SkillRequest.builder()
                .userId("u").sessionId("s").message("hello there")
                .invoiceImage(new byte[]{1, 2, 3}).imageFilename("invoice.png").build();
        assertFalse(skill.canHandle(request), "Should not claim the message without the trigger phrase");
    }

    @Test
    void matchesWhenBothTriggerPhraseAndImagePresent() {
        SkillRequest request = SkillRequest.builder()
                .userId("u").sessionId("s").message("please scan invoice for me")
                .invoiceImage(new byte[]{1, 2, 3}).imageFilename("invoice.png").build();
        assertTrue(skill.canHandle(request));
    }

    @Test
    void matchesGatewayQualifiedTriggerPhrase() {
        SkillRequest request = SkillRequest.builder()
                .userId("u").sessionId("s").message("scan invoice via stripe")
                .invoiceImage(new byte[]{1, 2, 3}).imageFilename("invoice.png").build();
        assertTrue(skill.canHandle(request));
    }
}
