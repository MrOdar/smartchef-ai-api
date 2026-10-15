package com.smartchef.api.dto;

public record FavoriteToggleResponse(
        Long recipeId,
        boolean favorite,
        String message) {
}
