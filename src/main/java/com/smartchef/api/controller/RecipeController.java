package com.smartchef.api.controller;

import com.smartchef.api.dto.ApiErrorResponse;
import com.smartchef.api.dto.FavoriteToggleResponse;
import com.smartchef.api.dto.RecipeResponse;
import com.smartchef.api.dto.ScanResultResponse;
import com.smartchef.api.security.SmartChefUserDetails;
import com.smartchef.api.service.RecipeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping(value = "/api/v1/recipes",
        produces = MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
@Tag(name = "Recetas con IA",
        description = "Generacion de recetas a partir de fotos, historial y favoritos")
public class RecipeController {

    private final RecipeService recipeService;

    public RecipeController(RecipeService recipeService) {
        this.recipeService = recipeService;
    }

    @PostMapping(value = "/generate-from-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Generar 3 recetas a partir de una foto",
            description = """
                    Sube una foto de tu despensa o refrigeradora (JPEG, PNG o WEBP, hasta 15MB).
                    La imagen se redimensiona en memoria si supera 1MB, se analiza con Google
                    Gemini 1.5 Flash y se devuelven los ingredientes detectados junto a 3 recetas
                    de aprovechamiento. Funciona con o sin token JWT: si envias el token, el
                    escaneo queda asociado a tu historial.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ingredientes detectados y recetas generadas"),
            @ApiResponse(responseCode = "400", description = "Imagen ausente o formato no soportado",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "413", description = "La imagen supera 15MB",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "El servicio de IA no esta disponible",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<ScanResultResponse> generateFromImage(
            @Parameter(description = "Foto de la despensa o refrigeradora",
                    content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE))
            @RequestParam("image") MultipartFile image,
            @AuthenticationPrincipal SmartChefUserDetails principal) {

        Long userId = principal == null ? null : principal.getId();
        return ResponseEntity.ok(recipeService.generateFromImage(image, userId));
    }

    @GetMapping("/history")
    @Operation(summary = "Historial de escaneos del usuario",
            description = "Devuelve las sesiones previas con sus recetas, mas recientes primero.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Historial del usuario"),
            @ApiResponse(responseCode = "401", description = "Token ausente o invalido",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<List<ScanResultResponse>> history(
            @AuthenticationPrincipal SmartChefUserDetails principal) {
        return ResponseEntity.ok(recipeService.history(principal.getId()));
    }

    @PostMapping("/{id}/favorite")
    @Operation(summary = "Alternar una receta como favorita",
            description = "Si la receta no estaba guardada la agrega; si ya lo estaba la elimina.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Estado de favorito actualizado"),
            @ApiResponse(responseCode = "401", description = "Token ausente o invalido",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "La receta no existe",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<FavoriteToggleResponse> toggleFavorite(
            @PathVariable("id") Long id,
            @AuthenticationPrincipal SmartChefUserDetails principal) {
        return ResponseEntity.ok(recipeService.toggleFavorite(id, principal.getId()));
    }

    @GetMapping("/favorites")
    @Operation(summary = "Recetas guardadas en favoritos",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista de recetas favoritas"),
            @ApiResponse(responseCode = "401", description = "Token ausente o invalido",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<List<RecipeResponse>> favorites(
            @AuthenticationPrincipal SmartChefUserDetails principal) {
        return ResponseEntity.ok(recipeService.favorites(principal.getId()));
    }
}
