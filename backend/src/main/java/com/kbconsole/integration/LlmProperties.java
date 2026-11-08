package com.kbconsole.integration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "kb.llm")
public class LlmProperties {
    private boolean enabled = true;
    private String baseUrl = "http://localhost:11434";
    private String model = "qwen2.5:3b-instruct";
    private int numCtx = 8192;
    private double temperature = 0.1;
    private int timeoutSeconds = 120;
    private int maxContextChunks = 5;
    private int maxContextChars = 6000;
    private boolean verifyOnStartup = true;
    private boolean think = false;
}
