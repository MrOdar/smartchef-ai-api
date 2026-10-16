package com.smartchef.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartchef.api.dto.FavoriteToggleResponse;
import com.smartchef.api.dto.GeminiRecipeResult;
import com.smartchef.api.dto.RecipeResponse;
import com.smartchef.api.dto.ScanResultResponse;
import com.smartchef.api.entity.Difficulty;
import com.smartchef.api.entity.FavoriteRecipe;
import com.smartchef.api.entity.Recipe;
import com.smartchef.api.entity.RecipeIngredient;
import com.smartchef.api.entity.ScanSession;
import com.smartchef.api.entity.User;
import com.smartchef.api.exception.GeminiServiceException;
import com.smartchef.api.exception.ResourceNotFoundException;
import com.smartchef.api.repository.FavoriteRecipeRepository;
import com.smartchef.api.repository.RecipeRepository;
import com.smartchef.api.repository.ScanSessionRepository;
import com.smartchef.api.repository.UserRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Orquesta el flujo completo: imagen -> Gemini -> persistencia -> DTOs. */
@Service
public class RecipeService {

    private static final Logger log = LoggerFactory.getLogger(RecipeService.class);
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_INGREDIENT_NAME_LENGTH = 150;
    private static final int MAX_QUANTITY_LENGTH = 100;

    private final ImageProcessingService imageProcessingService;
    private final GeminiVisionService geminiVisionService;
    private final ScanSessionRepository scanSessionRepository;
    private final RecipeRepository recipeRepository;
    private final FavoriteRecipeRepository favoriteRecipeRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public RecipeService(ImageProcessingService imageProcessingService,
                         GeminiVisionService geminiVisionService,
                         ScanSessionRepository scanSessionRepository,
                         RecipeRepository recipeRepository,
                         FavoriteRecipeRepository favoriteRecipeRepository,
                         UserRepository userRepository,
                         ObjectMapper objectMapper) {
        this.imageProcessingService = imageProcessingService;
        this.geminiVisionService = geminiVisionService;
        this.scanSessionRepository = scanSessionRepository;
        this.recipeRepository = recipeRepository;
        this.favoriteRecipeRepository = favoriteRecipeRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ScanResultResponse generateFromImage(MultipartFile image, Long userId) {
        ImageProcessingService.ProcessedImage processed = imageProcessingService.process(image);

        GeminiRecipeResult result =
                geminiVisionService.analyzeImage(processed.data(), processed.mimeType());

        if (result.recipes().isEmpty()) {
            throw new GeminiServiceException(
                    "No se detectaron alimentos en la imagen. Prueba con una foto mas clara de tu despensa.");
        }

        List<String> detectedIngredients = normalizeDetected(result);

        ScanSession session = new ScanSession();
        session.setUser(userId == null ? null : userRepository.findById(userId).orElse(null));
        session.setDetectedIngredientsSummary(String.join(", ", detectedIngredients));

        for (GeminiRecipeResult.GeneratedRecipe generated : result.recipes()) {
            session.addRecipe(toRecipeEntity(generated));
        }

        ScanSession saved = scanSessionRepository.save(session);
        log.info("Sesion de escaneo {} creada con {} recetas y {} ingredientes detectados",
                saved.getId(), saved.getRecipes().size(), detectedIngredients.size());

        Set<Long> favoriteIds = favoriteIdsFor(userId);

        return new ScanResultResponse(
                saved.getId(),
                detectedIngredients,
                saved.getRecipes().stream().map(r -> toResponse(r, favoriteIds)).toList(),
                saved.getCreatedAt());
    }

    /**
     * Persiste el resultado precalculado de la demo como una sesion real sin
     * usuario. Lo usa {@link DemoDataSeeder} al arrancar; no invoca a Gemini.
     */
    @Transactional
    public ScanSession seedDemoSession(GeminiRecipeResult result) {
        ScanSession session = new ScanSession();
        session.setUser(null);
        session.setDemo(true);
        session.setDetectedIngredientsSummary(String.join(", ", normalizeDetected(result)));

        for (GeminiRecipeResult.GeneratedRecipe generated : result.recipes()) {
            session.addRecipe(toRecipeEntity(generated));
        }

        return scanSessionRepository.save(session);
    }

    /**
     * Devuelve la sesion demo ya sembrada. No hay ninguna llamada a la IA en
     * este camino: es una unica lectura de base de datos.
     */
    @Transactional(readOnly = true)
    public ScanResultResponse demo(Long userId) {
        ScanSession session = scanSessionRepository.findDemoSessions().stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "La sesion de demostracion no esta disponible todavia"));

        Set<Long> favoriteIds = favoriteIdsFor(userId);

        return new ScanResultResponse(
                session.getId(),
                splitSummary(session.getDetectedIngredientsSummary()),
                session.getRecipes().stream().map(r -> toResponse(r, favoriteIds)).toList(),
                session.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<ScanResultResponse> history(Long userId) {
        Set<Long> favoriteIds = favoriteIdsFor(userId);

        return scanSessionRepository.findHistoryByUserId(userId).stream()
                .map(session -> new ScanResultResponse(
                        session.getId(),
                        splitSummary(session.getDetectedIngredientsSummary()),
                        session.getRecipes().stream().map(r -> toResponse(r, favoriteIds)).toList(),
                        session.getCreatedAt()))
                .toList();
    }

    @Transactional
    public FavoriteToggleResponse toggleFavorite(Long recipeId, Long userId) {
        Recipe recipe = recipeRepository.findById(recipeId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No existe una receta con id " + recipeId));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No existe el usuario autenticado con id " + userId));

        Optional<FavoriteRecipe> existing =
                favoriteRecipeRepository.findByUserAndRecipe(userId, recipeId);

        if (existing.isPresent()) {
            favoriteRecipeRepository.delete(existing.get());
            return new FavoriteToggleResponse(recipeId, false, "Receta eliminada de favoritos");
        }

        favoriteRecipeRepository.save(new FavoriteRecipe(user, recipe));
        return new FavoriteToggleResponse(recipeId, true, "Receta guardada en favoritos");
    }

    @Transactional(readOnly = true)
    public List<RecipeResponse> favorites(Long userId) {
        return favoriteRecipeRepository.findAllByUserIdWithRecipes(userId).stream()
                .map(favorite -> RecipeResponse.from(
                        favorite.getRecipe(),
                        readSteps(favorite.getRecipe().getStepsJson()),
                        true))
                .toList();
    }

    // --- Helpers de mapeo ---------------------------------------------------

    private Recipe toRecipeEntity(GeminiRecipeResult.GeneratedRecipe generated) {
        Recipe recipe = new Recipe();
        recipe.setTitle(truncate(blankToDefault(generated.title(), "Receta sugerida"), MAX_TITLE_LENGTH));
        recipe.setDescription(generated.description());
        recipe.setPrepTimeMinutes(generated.prepTimeMinutes());
        recipe.setDifficulty(Difficulty.fromText(generated.difficulty()));
        recipe.setCaloriesEstimate(generated.caloriesEstimate());
        recipe.setStepsJson(writeSteps(generated.steps()));
        recipe.setChefTip(blankToNull(generated.chefTip()));

        for (GeminiRecipeResult.GeneratedIngredient ingredient : generated.ingredients()) {
            if (ingredient.name() == null || ingredient.name().isBlank()) {
                continue;
            }
            RecipeIngredient entity = new RecipeIngredient();
            entity.setName(truncate(ingredient.name().trim(), MAX_INGREDIENT_NAME_LENGTH));
            entity.setQuantity(truncate(ingredient.quantity(), MAX_QUANTITY_LENGTH));
            entity.setAvailable(ingredient.availableOrFalse());
            recipe.addIngredient(entity);
        }

        return recipe;
    }

    private RecipeResponse toResponse(Recipe recipe, Set<Long> favoriteIds) {
        return RecipeResponse.from(
                recipe,
                readSteps(recipe.getStepsJson()),
                favoriteIds.contains(recipe.getId()));
    }

    private Set<Long> favoriteIdsFor(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        return favoriteRecipeRepository.findAllByUserIdWithRecipes(userId).stream()
                .map(favorite -> favorite.getRecipe().getId())
                .collect(java.util.stream.Collectors.toSet());
    }

    private List<String> normalizeDetected(GeminiRecipeResult result) {
        Set<String> unique = new LinkedHashSet<>();
        for (String ingredient : result.detectedIngredients()) {
            if (ingredient != null && !ingredient.isBlank()) {
                unique.add(ingredient.trim());
            }
        }
        // Complemento: ingredientes marcados como disponibles en las recetas.
        for (GeminiRecipeResult.GeneratedRecipe recipe : result.recipes()) {
            for (GeminiRecipeResult.GeneratedIngredient ingredient : recipe.ingredients()) {
                if (ingredient.availableOrFalse()
                        && ingredient.name() != null && !ingredient.name().isBlank()) {
                    unique.add(ingredient.name().trim());
                }
            }
        }
        return new ArrayList<>(unique);
    }

    private String writeSteps(List<String> steps) {
        try {
            return objectMapper.writeValueAsString(steps == null ? List.of() : steps);
        } catch (Exception e) {
            log.warn("No se pudieron serializar los pasos: {}", e.getMessage());
            return "[]";
        }
    }

    private List<String> readSteps(String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(stepsJson, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("steps_json corrupto en base de datos: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> splitSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return List.of();
        }
        return List.of(summary.split("\\s*,\\s*"));
    }

    private String blankToDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    /** Un tip vacio se guarda como nulo para que el frontend omita el recuadro. */
    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    public Map<String, Object> diagnoseGemini() {
        return geminiVisionService.diagnose();
    }
}
