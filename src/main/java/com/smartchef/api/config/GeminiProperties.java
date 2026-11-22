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
        if (apiKey != null) {
            apiKey = apiKey.trim();
            if ((apiKey.startsWith("\"") && apiKey.endsWith("\""))
                    || (apiKey.startsWith("'") && apiKey.endsWith("'"))) {
                apiKey = apiKey.substring(1, apiKey.length() - 1).trim();
            }
        }
        if (model == null || model.isBlank()) {
            model = "gemini-3.5-flash-lite";
        } else {
            model = model.trim();
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://generativelanguage.googleapis.com/v1beta";
        } else {
            baseUrl = baseUrl.trim();
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 60;
        }
        if (temperature <= 0) {
            temperature = 0.4;
        }
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
