package com.smartchef.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Estructura JSON que exigimos a Gemini 1.5 Flash. Se mapea directamente con
 * Jackson tras limpiar la respuesta del modelo.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiRecipeResult(

        @JsonProperty("detected_ingredients")
        List<String> detectedIngredients,

        @JsonProperty("recipes")
        List<GeneratedRecipe> recipes) {

    public List<String> detectedIngredients() {
        return detectedIngredients == null ? List.of() : detectedIngredients;
    }

    public List<GeneratedRecipe> recipes() {
        return recipes == null ? List.of() : recipes;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GeneratedRecipe(

            @JsonProperty("title")
            String title,

            @JsonProperty("description")
            String description,

            @JsonProperty("prep_time_minutes")
            Integer prepTimeMinutes,

            @JsonProperty("difficulty")
            String difficulty,

            @JsonProperty("calories_estimate")
            Integer caloriesEstimate,

            @JsonProperty("steps")
            List<String> steps,

            @JsonProperty("ingredients")
            List<GeneratedIngredient> ingredients) {

        public List<String> steps() {
            return steps == null ? List.of() : steps;
        }

        public List<GeneratedIngredient> ingredients() {
            return ingredients == null ? List.of() : ingredients;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GeneratedIngredient(

            @JsonProperty("name")
            String name,

            @JsonProperty("quantity")
            String quantity,

            @JsonProperty("is_available")
            Boolean available) {

        public boolean availableOrFalse() {
            return Boolean.TRUE.equals(available);
        }
    }
}
