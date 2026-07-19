package com.finvoicebot.strategy;

import com.finvoicebot.dto.ChatResponse;
import com.finvoicebot.dto.SkillRequest;
import com.finvoicebot.exception.SkillExecutionException;
import com.finvoicebot.skill.ChatSkill;
import com.finvoicebot.skill.impl.HelpSkill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Strategy-pattern router for the Agent Skill Strategy: holds the ordered list of registered
 * {@link ChatSkill} beans (see {@code SkillConfig}) and, for every incoming message, executes
 * the first skill whose {@link ChatSkill#canHandle} returns true.
 *
 * <p>Routing order is deterministic (by ascending {@link ChatSkill#priority()}), which makes
 * the flow auditable: given a message, you can always predict — and log — which skill will run
 * before any of them execute, which matters for a payments-adjacent audit trail.
 *
 * <p>If no skill claims the message, control falls back to {@link HelpSkill} directly, so the
 * user is never left without a response.
 */
@Slf4j
@Component
public class AgentSkillStrategy {

    private final List<ChatSkill> orderedSkills;
    private final HelpSkill fallbackSkill;

    public AgentSkillStrategy(List<ChatSkill> skills, HelpSkill fallbackSkill) {
        this.orderedSkills = skills.stream()
                .sorted(Comparator.comparingInt(ChatSkill::priority))
                .toList();
        this.fallbackSkill = fallbackSkill;
        log.info("AgentSkillStrategy initialized with {} skills, routing order: {}",
                orderedSkills.size(),
                orderedSkills.stream().map(ChatSkill::name).toList());
    }

    /**
     * Routes a single user turn to the first matching skill.
     */
    public ChatResponse route(SkillRequest request) {
        for (ChatSkill skill : orderedSkills) {
            try {
                if (skill.canHandle(request)) {
                    log.info("Routing message to {}", skill.name());
                    return skill.handle(request);
                }
            } catch (Exception e) {
                // A skill's canHandle() must never crash the router — treat it as "does not match".
                log.error("canHandle() threw for skill {}, skipping it", skill.name(), e);
            }
        }

        log.info("No skill matched — falling back to HelpSkill");
        try {
            return fallbackSkill.handle(request);
        } catch (Exception e) {
            throw new SkillExecutionException("AgentSkillStrategy",
                    "Something went wrong and I couldn't even show the help menu. Please try again.", e);
        }
    }
}
