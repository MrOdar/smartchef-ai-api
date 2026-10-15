package com.smartchef.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartchef.api.config.GeminiProperties;
import com.smartchef.api.dto.GeminiRecipeResult;
import com.smartchef.api.exception.GeminiServiceException;
import com.smartchef.api.service.GeminiVisionService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/** Pruebas unitarias del cliente de Gemini: 100% offline, sin API key real. */
@ExtendWith(MockitoExtension.class)
class GeminiVisionServiceTest {

    private static final byte[] IMAGE = "fake-image-bytes".getBytes(StandardCharsets.UTF_8);

    @Mock
    private RestTemplate restTemplate;

    private ObjectMapper objectMapper;
    private GeminiVisionService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        GeminiProperties properties = new GeminiProperties(
                "test-api-key", "gemini-1.5-flash",
                "https://generativelanguage.googleapis.com/v1beta", 30, 0.4);
        service = new GeminiVisionService(restTemplate, properties, objectMapper);
    }

    private String geminiEnvelope(String modelText) {
        try {
            return objectMapper.createObjectNode()
                    .set("candidates", objectMapper.createArrayNode()
                            .add(objectMapper.createObjectNode()
                                    .set("content", objectMapper.createObjectNode()
                                            .set("parts", objectMapper.createArrayNode()
                                                    .add(objectMapper.createObjectNode()
                                                            .put("text", modelText))))))
                    .toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String VALID_JSON = """
            {
              "detected_ingredients": ["tomate", "huevo"],
              "recipes": [
                {
                  "title": "Tortilla de tomate",
                  "description": "Rapida y economica",
                  "prep_time_minutes": 15,
                  "difficulty": "FACIL",
                  "calories_estimate": 320,
                  "steps": ["Batir los huevos", "Saltear el tomate", "Cuajar la tortilla"],
                  "ingredients": [
                    { "name": "tomate", "quantity": "2 unidades", "is_available": true },
                    { "name": "perejil", "quantity": "al gusto", "is_available": false }
                  ]
                }
              ]
            }
            """;

    @Test
    @DisplayName("Parsea una respuesta JSON limpia de Gemini")
    void parsesCleanJsonResponse() {
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(geminiEnvelope(VALID_JSON));

        GeminiRecipeResult result = service.analyzeImage(IMAGE, "image/jpeg");

        assertThat(result.detectedIngredients()).containsExactly("tomate", "huevo");
        assertThat(result.recipes()).hasSize(1);

        GeminiRecipeResult.GeneratedRecipe recipe = result.recipes().get(0);
        assertThat(recipe.title()).isEqualTo("Tortilla de tomate");
        assertThat(recipe.prepTimeMinutes()).isEqualTo(15);
        assertThat(recipe.difficulty()).isEqualTo("FACIL");
        assertThat(recipe.caloriesEstimate()).isEqualTo(320);
        assertThat(recipe.steps()).hasSize(3);
        assertThat(recipe.ingredients()).hasSize(2);
        assertThat(recipe.ingredients().get(0).availableOrFalse()).isTrue();
        assertThat(recipe.ingredients().get(1).availableOrFalse()).isFalse();
    }

    @Test
    @DisplayName("Limpia los bloques markdown residuales antes de deserializar")
    void stripsMarkdownFencesFromModelOutput() {
        String fenced = "```json\n" + VALID_JSON + "\n```";
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(geminiEnvelope(fenced));

        GeminiRecipeResult result = service.analyzeImage(IMAGE, "image/jpeg");

        assertThat(result.recipes()).hasSize(1);
        assertThat(result.recipes().get(0).title()).isEqualTo("Tortilla de tomate");
    }

    @Test
    @DisplayName("Descarta texto conversacional antes del JSON")
    void stripsLeadingProseFromModelOutput() {
        String noisy = "Aqui tienes las recetas solicitadas:\n" + VALID_JSON;
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(geminiEnvelope(noisy));

        assertThat(service.analyzeImage(IMAGE, "image/jpeg").recipes()).hasSize(1);
    }

    @Test
    @DisplayName("El cuerpo de la peticion fuerza JSON estricto e incluye la imagen en Base64")
    void requestBodyEnforcesStrictJson() throws Exception {
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(geminiEnvelope(VALID_JSON));

        service.analyzeImage(IMAGE, "image/png");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate)
                .postForObject(anyString(), captor.capture(), eq(String.class));

        JsonNode body = objectMapper.readTree(captor.getValue().getBody());

        assertThat(body.at("/generationConfig/response_mime_type").asText())
                .isEqualTo("application/json");
        assertThat(body.at("/generationConfig/temperature").asDouble()).isEqualTo(0.4);

        JsonNode parts = body.at("/contents/0/parts");
        assertThat(parts.get(0).path("text").asText()).contains("chef profesional");
        assertThat(parts.get(1).at("/inline_data/mime_type").asText()).isEqualTo("image/png");
        assertThat(parts.get(1).at("/inline_data/data").asText())
                .isEqualTo(Base64.getEncoder().encodeToString(IMAGE));
    }

    @Test
    @DisplayName("Falla con 503 cuando Gemini devuelve un JSON invalido")
    void failsOnMalformedJson() {
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(geminiEnvelope("esto no es json en absoluto"));

        assertThatThrownBy(() -> service.analyzeImage(IMAGE, "image/jpeg"))
                .isInstanceOf(GeminiServiceException.class)
                .hasMessageContaining("formato inesperado");
    }

    @Test
    @DisplayName("Propaga el error de la API de Gemini de forma controlada")
    void surfacesApiError() {
        String errorPayload = """
                { "error": { "code": 400, "message": "API key not valid" } }
                """;
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(errorPayload);

        assertThatThrownBy(() -> service.analyzeImage(IMAGE, "image/jpeg"))
                .isInstanceOf(GeminiServiceException.class)
                .hasMessageContaining("API key not valid");
    }

    @Test
    @DisplayName("Convierte los fallos de red en GeminiServiceException")
    void wrapsNetworkFailures() {
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("connection timed out"));

        assertThatThrownBy(() -> service.analyzeImage(IMAGE, "image/jpeg"))
                .isInstanceOf(GeminiServiceException.class)
                .hasMessageContaining("No se pudo contactar");
    }

    @Test
    @DisplayName("Exige GEMINI_API_KEY configurada")
    void requiresApiKey() {
        GeminiVisionService withoutKey = new GeminiVisionService(
                restTemplate,
                new GeminiProperties("", "gemini-1.5-flash", "https://example.invalid/v1beta", 30, 0.4),
                objectMapper);

        assertThatThrownBy(() -> withoutKey.analyzeImage(IMAGE, "image/jpeg"))
                .isInstanceOf(GeminiServiceException.class)
                .hasMessageContaining("GEMINI_API_KEY");
    }
}
