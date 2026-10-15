package com.smartchef.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "smartchef.jwt")
public record JwtProperties(
        String secret,
        long expirationMs) {

    public JwtProperties {
        if (expirationMs <= 0) {
            expirationMs = 86_400_000L;
        }
    }

    public long expirationSeconds() {
        return expirationMs / 1000L;
    }
}
