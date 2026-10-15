package com.smartchef.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "smartchef.gemini")
public record GeminiProperties(
        String apiKey,
        String model,
        String baseUrl,
        int timeoutSeconds,
        double temperature) {

    public GeminiProperties {
        if (model == null || model.isBlank()) {
            model = "gemini-1.5-flash";
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://generativelanguage.googleapis.com/v1beta";
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 60;
        }
        if (temperature <= 0) {
            temperature = 0.4;
        }
    }

    public String generateContentUrl() {
        return "%s/models/%s:generateContent?key=%s".formatted(baseUrl, model, apiKey);
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
