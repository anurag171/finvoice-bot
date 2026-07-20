package com.finvoicebot.skill.impl;

import com.finvoicebot.dto.SkillRequest;
import com.finvoicebot.skill.ChatSkill;
import com.finvoicebot.dto.ChatResponse;

public class ListeningSkill implements ChatSkill {

    @Override
    public boolean canHandle(SkillRequest request){
        return request.normalizedMessage().contains("listen");
    }

    @Override
    public ChatResponse handle(SkillRequest request) {
        return ChatResponse.builder()
                .text("Listening for audio input...")
                .build();
    }

    @Override
    public String name() {
        return "ListeningSkill";
    }
    
    @Override
    public String triggerDescription() {
        return "listen input — start listening for audio input";
    }

    @Override
    public int priority() {
        return 50;
    }

}
