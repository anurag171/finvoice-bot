package com.finvoicebot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${finvoice.audio.storage-dir:./audio-clips}")
    private String audioStorageDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = audioStorageDir.endsWith("/") ? audioStorageDir : audioStorageDir + "/";
        registry.addResourceHandler("/audio/**")
                .addResourceLocations("file:" + location);
    }
}
