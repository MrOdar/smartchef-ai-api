package com.smartchef.api.ratelimit;

public class TooManyRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
