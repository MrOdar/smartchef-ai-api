package com.smartchef.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "smartchef.image")
public record ImageProperties(
        int maxWidth,
        long resizeThresholdBytes) {

    public ImageProperties {
        if (maxWidth <= 0) {
            maxWidth = 1024;
        }
        if (resizeThresholdBytes <= 0) {
            resizeThresholdBytes = 1_048_576L;
        }
    }
}
