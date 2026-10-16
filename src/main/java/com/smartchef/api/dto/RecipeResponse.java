package com.smartchef.api.dto;

import com.smartchef.api.entity.Recipe;
import java.time.Instant;
import java.util.List;

public record RecipeResponse(
        Long id,
        String title,
        String description,
        Integer prepTimeMinutes,
        String difficulty,
        Integer caloriesEstimate,
        List<String> steps,
        String chefTip,
        List<IngredientResponse> ingredients,
        Instant createdAt,
        Boolean favorite) {

    public static RecipeResponse from(Recipe recipe, List<String> steps, Boolean favorite) {
        return new RecipeResponse(
                recipe.getId(),
                recipe.getTitle(),
                recipe.getDescription(),
                recipe.getPrepTimeMinutes(),
                recipe.getDifficulty() == null ? null : recipe.getDifficulty().name(),
                recipe.getCaloriesEstimate(),
                steps,
                recipe.getChefTip(),
                recipe.getIngredients().stream().map(IngredientResponse::from).toList(),
                recipe.getCreatedAt(),
                favorite);
    }
}
