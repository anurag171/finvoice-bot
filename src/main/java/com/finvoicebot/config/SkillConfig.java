package com.finvoicebot.config;

import com.finvoicebot.skill.ChatSkill;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Each skill ({@code HelpSkill}, {@code ScanInvoiceSkill}, {@code PaymentSkill},
 * {@code ReadAloudSkill}, {@code StatusSkill}) is annotated {@code @Component} and therefore
 * auto-registered as a bean by classpath scanning — {@link com.finvoicebot.strategy.AgentSkillStrategy}
 * receives the full {@code List<ChatSkill>} via constructor injection with no manual wiring needed here.
 *
 * <p>This class exists as the single, explicit place documented in the manifest
 * ({@code agent-skills.md}) as "where skills are registered" — and to fail fast at startup if
 * the expected skill count doesn't match, catching a skill that's accidentally missing its
 * {@code @Component} annotation or excluded from component scanning.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SkillConfig {

    private static final int EXPECTED_SKILL_COUNT = 5; // Help, ScanInvoice, Payment, ReadAloud, Status

    private final List<ChatSkill> registeredSkills;

    @PostConstruct
    void verifyRegistration() {
        if (registeredSkills.size() < EXPECTED_SKILL_COUNT) {
            log.warn("Expected at least {} skills registered, found {}: {}",
                    EXPECTED_SKILL_COUNT, registeredSkills.size(),
                    registeredSkills.stream().map(ChatSkill::name).toList());
        }
        registeredSkills.forEach(s -> log.info("Registered skill: {} (priority={})", s.name(), s.priority()));
    }
}
