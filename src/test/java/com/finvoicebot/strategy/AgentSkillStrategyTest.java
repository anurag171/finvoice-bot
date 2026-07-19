package com.finvoicebot.strategy;

import com.finvoicebot.dto.ChatResponse;
import com.finvoicebot.dto.SkillRequest;
import com.finvoicebot.skill.ChatSkill;
import com.finvoicebot.skill.impl.HelpSkill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

class AgentSkillStrategyTest {

    private ChatSkill highPriorityMatch;
    private ChatSkill lowPriorityMatch;
    private ChatSkill neverMatches;
    private HelpSkill helpSkill;

    @BeforeEach
    void setUp() {
        highPriorityMatch = mock(ChatSkill.class);
        when(highPriorityMatch.priority()).thenReturn(10);
        when(highPriorityMatch.name()).thenReturn("HighPriority");

        lowPriorityMatch = mock(ChatSkill.class);
        when(lowPriorityMatch.priority()).thenReturn(50);
        when(lowPriorityMatch.name()).thenReturn("LowPriority");

        neverMatches = mock(ChatSkill.class);
        when(neverMatches.priority()).thenReturn(5);
        when(neverMatches.name()).thenReturn("NeverMatches");
        when(neverMatches.canHandle(any())).thenReturn(false);

        helpSkill = mock(HelpSkill.class);
        when(helpSkill.handle(any())).thenReturn(ChatResponse.of("HelpSkill", "help text"));
    }

    @Test
    void routesToFirstMatchingSkillInPriorityOrder() {
        when(highPriorityMatch.canHandle(any())).thenReturn(true);
        when(highPriorityMatch.handle(any())).thenReturn(ChatResponse.of("HighPriority", "handled by high"));
        when(lowPriorityMatch.canHandle(any())).thenReturn(true);
        when(lowPriorityMatch.handle(any())).thenReturn(ChatResponse.of("LowPriority", "handled by low"));

        AgentSkillStrategy strategy = new AgentSkillStrategy(
                List.of(lowPriorityMatch, neverMatches, highPriorityMatch), helpSkill);

        ChatResponse response = strategy.route(SkillRequest.builder().userId("u1").sessionId("s1").message("anything").build());

        assertEquals("HighPriority", response.getSkillName());
        verify(highPriorityMatch, times(1)).handle(any());
        verify(lowPriorityMatch, never()).handle(any());
    }

    @Test
    void fallsBackToHelpSkillWhenNothingMatches() {
        when(neverMatches.canHandle(any())).thenReturn(false);
        when(highPriorityMatch.canHandle(any())).thenReturn(false);
        when(lowPriorityMatch.canHandle(any())).thenReturn(false);

        AgentSkillStrategy strategy = new AgentSkillStrategy(
                List.of(highPriorityMatch, lowPriorityMatch, neverMatches), helpSkill);

        ChatResponse response = strategy.route(SkillRequest.builder().userId("u1").sessionId("s1").message("gibberish").build());

        assertEquals("HelpSkill", response.getSkillName());
        verify(helpSkill, times(1)).handle(any());
    }

    @Test
    void aSkillThrowingFromCanHandleIsSkippedNotFatal() {
        when(highPriorityMatch.canHandle(any())).thenThrow(new RuntimeException("boom"));
        when(lowPriorityMatch.canHandle(any())).thenReturn(true);
        when(lowPriorityMatch.handle(any())).thenReturn(ChatResponse.of("LowPriority", "handled anyway"));

        AgentSkillStrategy strategy = new AgentSkillStrategy(
                List.of(highPriorityMatch, lowPriorityMatch), helpSkill);

        ChatResponse response = strategy.route(SkillRequest.builder().userId("u1").sessionId("s1").message("x").build());

        assertEquals("LowPriority", response.getSkillName());
        assertFalse(response.getMessage().isBlank());
    }
}
