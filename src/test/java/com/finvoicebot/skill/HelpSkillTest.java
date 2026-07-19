package com.finvoicebot.skill;

import com.finvoicebot.dto.ChatResponse;
import com.finvoicebot.dto.SkillRequest;
import com.finvoicebot.skill.impl.HelpSkill;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelpSkillTest {

    private final HelpSkill skill = new HelpSkill();

    @Test
    void matchesHelpKeyword() {
        SkillRequest request = SkillRequest.builder().userId("u").sessionId("s").message("help").build();
        assertTrue(skill.canHandle(request));
    }

    @Test
    void matchesCaseInsensitivelyAndWithSurroundingText() {
        SkillRequest request = SkillRequest.builder().userId("u").sessionId("s").message("Can you show me the MENU please?").build();
        assertTrue(skill.canHandle(request));
    }

    @Test
    void doesNotMatchUnrelatedMessage() {
        SkillRequest request = SkillRequest.builder().userId("u").sessionId("s").message("scan invoice").build();
        assertFalse(skill.canHandle(request));
    }

    @Test
    void handleReturnsNonEmptyCommandList() {
        SkillRequest request = SkillRequest.builder().userId("u").sessionId("s").message("help").build();
        ChatResponse response = skill.handle(request);
        assertTrue(response.isSuccess());
        assertTrue(response.getMessage().toLowerCase().contains("scan invoice") || response.getMessage().length() > 0);
    }
}
