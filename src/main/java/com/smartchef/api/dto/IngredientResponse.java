package com.smartchef.api.dto;

import com.smartchef.api.entity.RecipeIngredient;

public record IngredientResponse(
        String name,
        String quantity,
        boolean available) {

    public static IngredientResponse from(RecipeIngredient ingredient) {
        return new IngredientResponse(
                ingredient.getName(),
                ingredient.getQuantity(),
                ingredient.isAvailable());
    }
}
