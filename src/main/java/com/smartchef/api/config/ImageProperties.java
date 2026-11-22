package com.smartchef.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "smartchef.image")
public record ImageProperties(
        int maxWidth,
        long resizeThresholdBytes,
        long maxPixels) {

    public ImageProperties {
        if (maxWidth <= 0) {
            maxWidth = 1024;
        }
        if (resizeThresholdBytes <= 0) {
            resizeThresholdBytes = 1_048_576L;
        }
        if (maxPixels <= 0) {
            // 50 Mpx: cualquier camara de telefono cabe, una bomba no.
            maxPixels = 50_000_000L;
        }
    }
}
