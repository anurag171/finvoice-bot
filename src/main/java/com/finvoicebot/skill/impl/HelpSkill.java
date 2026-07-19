package com.finvoicebot.skill.impl;

import com.finvoicebot.dto.ChatResponse;
import com.finvoicebot.dto.SkillRequest;
import com.finvoicebot.skill.ChatSkill;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Trigger: "help", "commands", "what can you do", "menu".
 * Also serves as the strategy's fallback when no other skill matches (see
 * {@link com.finvoicebot.strategy.AgentSkillStrategy}), so the user is never left without
 * a response.
 *
 * <p>Command descriptions are declared here directly rather than discovered by injecting the
 * full {@code List<ChatSkill>} — a self-referencing collection injection (this bean depending
 * on a list that includes itself) is fragile under Spring's circular-dependency resolution,
 * and the four skills are a small, stable set anyway. Keep this list in sync with
 * {@code agent-skills.md}.
 */
@Component
public class HelpSkill implements ChatSkill {

    private static final List<String> HELP_TRIGGERS = List.of("help", "commands", "what can you do", "menu");

    private static final List<String> COMMANDS = List.of(
            "scan invoice (attach an invoice image) — runs OCR, parsing, payment, and a spoken confirmation",
            "scan invoice via stripe / via razorpay — same as above, forcing a specific gateway",
            "read aloud — hear a spoken confirmation for the last scanned invoice again",
            "status [reference id] — fetch the current payment status from the gateway"
    );

    @Override
    public boolean canHandle(SkillRequest request) {
        String msg = request.normalizedMessage();
        return HELP_TRIGGERS.stream().anyMatch(msg::contains);
    }

    @Override
    public ChatResponse handle(SkillRequest request) {
        StringBuilder sb = new StringBuilder("Here's what I can do:\n\n");
        COMMANDS.forEach(c -> sb.append("• ").append(c).append("\n"));
        return ChatResponse.of(name(), sb.toString().stripTrailing());
    }

    @Override
    public String name() {
        return "HelpSkill";
    }

    @Override
    public String triggerDescription() {
        return "\"help\" — show this list of commands";
    }

    @Override
    public int priority() {
        // Lowest priority: canHandle() only matches explicit help keywords, but
        // AgentSkillStrategy also falls back to this skill's handle() directly
        // when no other skill matches at all, so users are never left with a dead end.
        return 1000;
    }
}
