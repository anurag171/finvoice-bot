package com.finvoicebot.exception;

/**
 * Thrown by a {@link com.finvoicebot.skill.ChatSkill} when it fails to complete its work
 * (OCR call failed, parser could not find required fields, gateway rejected the payment, etc).
 * The message should be safe to show directly to the end user.
 */
public class SkillExecutionException extends RuntimeException {

    private final String skillName;

    public SkillExecutionException(String skillName, String message) {
        super(message);
        this.skillName = skillName;
    }

    public SkillExecutionException(String skillName, String message, Throwable cause) {
        super(message, cause);
        this.skillName = skillName;
    }

    public String getSkillName() {
        return skillName;
    }
}
