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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
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
                new GeminiProperties("", "gemini-2.5-flash", "https://example.invalid/v1beta", 30, 0.4),
                objectMapper);

        assertThatThrownBy(() -> withoutKey.analyzeImage(IMAGE, "image/jpeg"))
                .isInstanceOf(GeminiServiceException.class)
                .hasMessageContaining("GEMINI_API_KEY");
    }

    @Test
    @DisplayName("No repite el descubrimiento de modelos en cada escaneo cuando falla")
    void cachesDiscoveryFailureAcrossScans() {
        // ListModels siempre falla; generateContent responde bien.
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("ListModels caido"));
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(geminiEnvelope(VALID_JSON));

        service.analyzeImage(IMAGE, "image/jpeg");
        service.analyzeImage(IMAGE, "image/jpeg");
        service.analyzeImage(IMAGE, "image/jpeg");

        // El descubrimiento se intenta una sola vez. En esa pasada recorre los
        // base URLs distintos (el configurado coincide con el de v1beta, asi que
        // quedan 2: v1beta y v1) y despues entra en el periodo de espera: los dos
        // escaneos siguientes no generan ni una llamada mas a ListModels.
        org.mockito.Mockito.verify(restTemplate, org.mockito.Mockito.times(2))
                .exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));

        // Las 3 fotos si llegaron a generar recetas.
        org.mockito.Mockito.verify(restTemplate, org.mockito.Mockito.times(3))
                .postForObject(anyString(), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("Usa los modelos descubiertos y no vuelve a listarlos")
    void cachesDiscoverySuccess() {
        String modelsPayload = """
                {
                  "models": [
                    { "name": "models/gemini-3.8-flash",
                      "supportedGenerationMethods": ["generateContent"] },
                    { "name": "models/text-embedding-004",
                      "supportedGenerationMethods": ["embedContent"] }
                  ]
                }
                """;
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(modelsPayload));
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(geminiEnvelope(VALID_JSON));

        assertThat(service.discoverAvailableModels(null))
                .containsExactly("gemini-3.8-flash");

        service.analyzeImage(IMAGE, "image/jpeg");
        service.analyzeImage(IMAGE, "image/jpeg");

        // Una sola llamada a ListModels pese a los dos escaneos posteriores.
        org.mockito.Mockito.verify(restTemplate, org.mockito.Mockito.times(1))
                .exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("El diagnostico no expone la API key ni el cuerpo de error de Google")
    void diagnoseDoesNotLeakSecrets() {
        String googleError = """
                {
                  "error": {
                    "code": 400,
                    "message": "API key not valid. Please pass a valid API key.",
                    "details": [ { "reason": "API_KEY_INVALID", "domain": "googleapis.com" } ]
                  }
                }
                """;
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.BAD_REQUEST, "Bad Request",
                        org.springframework.http.HttpHeaders.EMPTY,
                        googleError.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        Map<String, Object> diagnosis = service.diagnose();
        String serialized = diagnosis.toString();

        assertThat(diagnosis).containsEntry("status", "WARNING");
        assertThat(diagnosis).doesNotContainKey("maskedApiKey");
        assertThat(diagnosis).doesNotContainKey("log");

        // Ni la API key ni el detalle crudo de Google salen en la respuesta.
        assertThat(serialized)
                .doesNotContain("test-api-key")
                .doesNotContain("API_KEY_INVALID")
                .doesNotContain("Please pass a valid API key");

        // Lo que si queda es el estado HTTP, suficiente para diagnosticar.
        assertThat(serialized).contains("400");
    }

    @Test
    @DisplayName("Hace fallback al siguiente modelo si el primero retorna 404 Not Found")
    void fallsBackToNextModelWhen404() {
        HttpClientErrorException notFound = HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "Not Found", org.springframework.http.HttpHeaders.EMPTY,
                "{\"error\":{\"code\":404,\"message\":\"models/gemini-1.5-flash is not found for API version v1beta\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(notFound)
                .thenReturn(geminiEnvelope(VALID_JSON));

        GeminiRecipeResult result = service.analyzeImage(IMAGE, "image/jpeg");

        assertThat(result.recipes()).hasSize(1);
        assertThat(result.recipes().get(0).title()).isEqualTo("Tortilla de tomate");
    }

    @Test
    @DisplayName("Hace fallback al siguiente modelo si el primero sufre alta demanda (429/503)")
    void fallsBackToNextModelWhenHighDemand() {
        HttpClientErrorException highDemand = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", org.springframework.http.HttpHeaders.EMPTY,
                "{\"error\":{\"code\":429,\"message\":\"This model is currently experiencing high demand. Spikes in demand are usually temporary. Please try again later.\",\"status\":\"RESOURCE_EXHAUSTED\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(highDemand)
                .thenReturn(geminiEnvelope(VALID_JSON));

        GeminiRecipeResult result = service.analyzeImage(IMAGE, "image/jpeg");

        assertThat(result.recipes()).hasSize(1);
        assertThat(result.recipes().get(0).title()).isEqualTo("Tortilla de tomate");
    }
}
