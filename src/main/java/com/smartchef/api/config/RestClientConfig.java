package com.smartchef.api.config;

import java.time.Duration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

@Configuration
public class RestClientConfig {

    @Bean
    public RestTemplate geminiRestTemplate(RestTemplateBuilder builder, GeminiProperties properties) {
        RestTemplate restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .build();
        // Garantiza UTF-8 en la lectura de las respuestas del modelo.
        restTemplate.getMessageConverters()
                .removeIf(converter -> converter instanceof StringHttpMessageConverter);
        restTemplate.getMessageConverters()
                .add(0, new StringHttpMessageConverter(StandardCharsets.UTF_8));
        return restTemplate;
    }
}
