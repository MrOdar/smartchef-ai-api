package com.smartchef.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Lista de ingredientes con la que rehacer las recetas cuando el usuario
 * descarta alguno de los detectados.
 *
 * <p>Los nombres llegan del navegador y acaban dentro del prompt del modelo,
 * asi que los limites de aqui no son cosmetica: acotan cuanto texto ajeno
 * puede entrar. El saneado en si (control, blancos, duplicados) lo hace
 * {@code GeminiVisionService.sanitizeIngredients}, porque debe aplicarse
 * venga la lista de donde venga.</p>
 */
public record IngredientsRequest(

        @NotEmpty(message = "Indica al menos un ingrediente")
        @Size(max = 40, message = "Como maximo 40 ingredientes")
        List<
                @Size(min = 1, max = 60, message = "Cada ingrediente debe tener entre 1 y 60 caracteres")
                String> ingredients) {
}
