package com.smartchef.api.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Frenos de los endpoints publicos (prefijo {@code smartchef.rate-limit}).
 *
 * <p>Viven en memoria: hay una sola instancia en Render y un reinicio solo
 * devuelve el cupo antes de tiempo. El tope global de IA es el que protege la
 * cuota de Gemini aunque alguien rote IPs.
 */
@ConfigurationProperties(prefix = "smartchef.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        String clientIpHeader,
        Limit aiPerIp,
        Limit aiGlobal,
        Limit loginPerIp,
        Limit loginFailuresPerAccount,
        Limit registerPerIp) {

    public record Limit(int max, Duration window) {
    }
}
