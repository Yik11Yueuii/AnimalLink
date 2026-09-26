package org.animallink.intelligence.application;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class VersionedObservationPrompt {
    public static final String VERSION = "animal-observation-v1";
    private final String content;

    public VersionedObservationPrompt() {
        try {
            content = new ClassPathResource("prompts/animal-observation-v1.txt")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载多模态解析提示词", exception);
        }
        if (content.isBlank()) throw new IllegalStateException("多模态解析提示词不能为空");
    }

    public String content() { return content; }
}
