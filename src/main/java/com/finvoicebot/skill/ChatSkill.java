package com.finvoicebot.skill;

import com.finvoicebot.dto.ChatResponse;
import com.finvoicebot.dto.SkillRequest;

/**
 * Contract implemented by every skill in the Agent Skill Strategy.
 *
 * <p>Each skill is a self-contained unit of capability: it declares whether it
 * can handle a given user message ({@link #canHandle}), and if so, performs
 * the work and returns a {@link ChatResponse} ({@link #handle}).
 *
 * <p>Skills must be stateless and side-effect-free with respect to routing —
 * all conversational/session state lives in {@link SkillRequest} or is
 * persisted via injected services (never held as skill instance fields),
 * since skills are singleton Spring beans shared across all users.
 */
public interface ChatSkill {

    /**
     * @return true if this skill should handle the given request. Implementations
     * should be cheap (pattern/keyword matching) — no I/O — since the router may
     * call this on every skill for every message.
     */
    boolean canHandle(SkillRequest request);

    /**
     * Perform the skill's work. Only called when {@link #canHandle} returned true.
     *
     * @throws com.finvoicebot.exception.SkillExecutionException on any failure the
     * router/controller should translate into a user-facing error message.
     */
    ChatResponse handle(SkillRequest request);

    /**
     * @return a short human-readable name used in help text, audit logs, and routing traces.
     */
    String name();

    /**
     * @return the trigger phrase(s) shown to the user in the Help skill's command list.
     */
    String triggerDescription();

    /**
     * Routing priority — lower runs first. Lets more specific skills (e.g. "status <id>")
     * be checked before broad catch-alls. Default is neutral priority.
     */
    default int priority() {
        return 100;
    }
}
