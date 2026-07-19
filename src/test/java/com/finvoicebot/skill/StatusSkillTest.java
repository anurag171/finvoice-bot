package com.finvoicebot.skill;

import com.finvoicebot.dto.SkillRequest;
import com.finvoicebot.service.history.ChatHistoryService;
import com.finvoicebot.service.payment.PaymentGatewayFactory;
import com.finvoicebot.skill.impl.StatusSkill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class StatusSkillTest {

    private StatusSkill skill;

    @BeforeEach
    void setUp() {
        skill = new StatusSkill(mock(ChatHistoryService.class), mock(PaymentGatewayFactory.class));
    }

    @Test
    void matchesBareStatusKeyword() {
        SkillRequest request = SkillRequest.builder().userId("u").sessionId("s").message("status").build();
        assertTrue(skill.canHandle(request));
    }

    @Test
    void matchesStatusWithReferenceId() {
        SkillRequest request = SkillRequest.builder().userId("u").sessionId("s").message("status order_ABC123").build();
        assertTrue(skill.canHandle(request));
    }

    @Test
    void doesNotMatchUnrelatedMessage() {
        SkillRequest request = SkillRequest.builder().userId("u").sessionId("s").message("read aloud").build();
        assertFalse(skill.canHandle(request));
    }
}
