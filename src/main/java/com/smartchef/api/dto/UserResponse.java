package com.smartchef.api.dto;

import com.smartchef.api.entity.User;
import java.time.Instant;

public record UserResponse(
        Long id,
        String username,
        String email,
        String fullName,
        String role,
        Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFullName(),
                user.getRole().name(),
                user.getCreatedAt());
    }
}
