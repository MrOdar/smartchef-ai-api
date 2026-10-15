package com.smartchef.api.dto;

import java.time.Instant;
import java.util.Map;

public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        Map<String, String> fieldErrors) {

    public static ApiErrorResponse of(int status, String error, String message) {
        return new ApiErrorResponse(Instant.now(), status, error, message, null);
    }

    public static ApiErrorResponse of(int status, String error, String message,
                                      Map<String, String> fieldErrors) {
        return new ApiErrorResponse(Instant.now(), status, error, message, fieldErrors);
    }
}
