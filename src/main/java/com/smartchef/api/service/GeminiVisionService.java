package com.smartchef.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartchef.api.config.GeminiProperties;
import com.smartchef.api.dto.GeminiRecipeResult;
import com.smartchef.api.exception.GeminiServiceException;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Cliente de Google Gemini 1.5 Flash para analisis multimodal de imagenes.
 * Fuerza salida JSON mediante generationConfig.response_mime_type y limpia de
 * forma defensiva cualquier bloque markdown residual antes de deserializar.
 */
@Service
public class GeminiVisionService {

    private static final Logger log = LoggerFactory.getLogger(GeminiVisionService.class);

    private static final String MARKDOWN_FENCE = "```";

    static final String SYSTEM_PROMPT = """
            Eres un chef profesional experto en cocina de aprovechamiento. Analiza la imagen,
            detecta todos los ingredientes comestibles visibles y genera exactamente 3 recetas
            practicas que maximicen el uso de los ingredientes detectados, indicando claramente
            cuales estan presentes y cuales faltan.

            Responde UNICAMENTE con un objeto JSON valido, sin texto adicional y sin bloques de
            codigo markdown, con esta estructura exacta:

            {
              "detected_ingredients": ["ingrediente 1", "ingrediente 2"],
              "recipes": [
                {
                  "title": "Nombre de la receta",
                  "description": "Descripcion breve y apetitosa en una o dos frases",
                  "prep_time_minutes": 25,
                  "difficulty": "FACIL",
                  "calories_estimate": 450,
                  "steps": ["Paso 1 detallado", "Paso 2 detallado", "Paso 3 detallado"],
                  "ingredients": [
                    { "name": "tomate", "quantity": "2 unidades", "is_available": true },
                    { "name": "queso parmesano", "quantity": "50 g", "is_available": false }
                  ]
                }
              ]
            }

            Reglas obligatorias:
            - El array "recipes" debe contener exactamente 3 recetas.
            - "difficulty" solo puede ser FACIL, MEDIA o DIFICIL.
            - "prep_time_minutes" y "calories_estimate" son numeros enteros.
            - "is_available" es true solo si el ingrediente aparece en la imagen; false si hay que comprarlo.
            - Todos los textos en espanol neutro y cada receta con un minimo de 3 pasos.
            - Si la imagen no contiene alimentos, devuelve "detected_ingredients" vacio y "recipes" vacio.
            """;

    private static final List<String> FALLBACK_MODELS = List.of(
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-2.5-flash-lite",
            "gemini-1.5-flash-latest"
    );

    private final RestTemplate restTemplate;
    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;

    public GeminiVisionService(RestTemplate geminiRestTemplate,
                               GeminiProperties properties,
                               ObjectMapper objectMapper) {
        this.restTemplate = geminiRestTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public GeminiRecipeResult analyzeImage(byte[] imageBytes, String mimeType) {
        if (!properties.hasApiKey()) {
            throw new GeminiServiceException(
                    "GEMINI_API_KEY no esta configurada. Define la variable de entorno para usar el analisis con IA.");
        }

        String requestBody = buildRequestBody(imageBytes, mimeType);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

        List<String> candidateModels = getCandidateModels();
        String rawResponse = null;
        Exception lastException = null;

        for (int i = 0; i < candidateModels.size(); i++) {
            String targetModel = candidateModels.get(i);
            String url = properties.generateContentUrl(targetModel);
            try {
                log.info("Invocando Gemini AI con modelo: {}", targetModel);
                rawResponse = restTemplate.postForObject(url, entity, String.class);
                if (rawResponse != null && !rawResponse.isBlank()) {
                    if (isModelNotFoundError(rawResponse) && i < candidateModels.size() - 1) {
                        log.warn("Gemini informo que el modelo '{}' no esta disponible. Probando modelo alternativo...", targetModel);
                        continue;
                    }
                    break;
                }
            } catch (HttpStatusCodeException e) {
                lastException = e;
                if (isNotFoundOrUnsupported(e) && i < candidateModels.size() - 1) {
                    log.warn("El modelo '{}' devolvio error HTTP {} ({}). Probando modelo alternativo...",
                            targetModel, e.getStatusCode(), e.getStatusText());
                    continue;
                }
                throw new GeminiServiceException(
                        "No se pudo contactar al servicio de IA de Gemini: " + e.getMessage(), e);
            } catch (RestClientException e) {
                lastException = e;
                if (isNotFoundOrUnsupported(e) && i < candidateModels.size() - 1) {
                    log.warn("Fallo de llamada con modelo '{}': {}. Probando modelo alternativo...",
                            targetModel, e.getMessage());
                    continue;
                }
                throw new GeminiServiceException(
                        "No se pudo contactar al servicio de IA de Gemini: " + e.getMessage(), e);
            }
        }

        if (rawResponse == null || rawResponse.isBlank()) {
            if (lastException != null) {
                throw new GeminiServiceException(
                        "No se pudo contactar al servicio de IA de Gemini: " + lastException.getMessage(), lastException);
            }
            throw new GeminiServiceException("Gemini devolvio una respuesta vacia");
        }

        return parseRecipeResult(extractModelText(rawResponse));
    }

    private List<String> getCandidateModels() {
        List<String> candidates = new java.util.ArrayList<>();
        if (properties.model() != null && !properties.model().isBlank()) {
            candidates.add(properties.model().trim());
        }
        for (String fallback : FALLBACK_MODELS) {
            if (!candidates.contains(fallback)) {
                candidates.add(fallback);
            }
        }
        return candidates;
    }

    private boolean isNotFoundOrUnsupported(RestClientException e) {
        if (e instanceof HttpStatusCodeException statusEx) {
            if (statusEx.getStatusCode().value() == 404) {
                return true;
            }
        }
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        String lower = msg.toLowerCase();
        return lower.contains("404") || lower.contains("not found") || lower.contains("not supported");
    }

    private boolean isModelNotFoundError(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            JsonNode error = root.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                int code = error.path("code").asInt(0);
                String msg = error.path("message").asText("").toLowerCase();
                return code == 404 || msg.contains("not found") || msg.contains("not supported");
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private String buildRequestBody(byte[] imageBytes, String mimeType) {
        ObjectNode root = objectMapper.createObjectNode();

        ArrayNode contents = root.putArray("contents");
        ObjectNode userTurn = contents.addObject();
        userTurn.put("role", "user");
        ArrayNode parts = userTurn.putArray("parts");

        parts.addObject().put("text", SYSTEM_PROMPT);

        ObjectNode inlineDataPart = parts.addObject();
        ObjectNode inlineData = inlineDataPart.putObject("inline_data");
        inlineData.put("mime_type", mimeType);
        inlineData.put("data", Base64.getEncoder().encodeToString(imageBytes));

        ObjectNode generationConfig = root.putObject("generationConfig");
        generationConfig.put("response_mime_type", "application/json");
        generationConfig.put("temperature", properties.temperature());
        generationConfig.put("maxOutputTokens", 4096);

        return root.toString();
    }

    /** Extrae candidates[0].content.parts[*].text de la envoltura de Gemini. */
    private String extractModelText(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);

            JsonNode error = root.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                throw new GeminiServiceException(
                        "Gemini respondio con error: " + error.path("message").asText("desconocido"));
            }

            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                throw new GeminiServiceException("Gemini no devolvio ningun candidato de respuesta");
            }

            StringBuilder text = new StringBuilder();
            for (JsonNode part : candidates.get(0).path("content").path("parts")) {
                if (part.path("thought").asBoolean(false)) {
                    continue;
                }
                text.append(part.path("text").asText(""));
            }

            if (text.isEmpty()) {
                throw new GeminiServiceException("La respuesta de Gemini no contiene texto analizable");
            }
            return text.toString();

        } catch (GeminiServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new GeminiServiceException("No se pudo interpretar la respuesta de Gemini", e);
        }
    }

    private GeminiRecipeResult parseRecipeResult(String modelText) {
        String cleaned = stripMarkdownFences(modelText);
        try {
            return objectMapper.readValue(cleaned, GeminiRecipeResult.class);
        } catch (Exception e) {
            log.error("JSON invalido devuelto por Gemini: {}", abbreviate(cleaned));
            throw new GeminiServiceException(
                    "La IA devolvio un formato inesperado. Intenta de nuevo con otra foto.", e);
        }
    }

    /**
     * Metodo defensivo: elimina bloques markdown de codigo, prefijos de texto y
     * espacios antes de pasar el contenido a Jackson.
     */
    static String stripMarkdownFences(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.strip();

        if (text.startsWith(MARKDOWN_FENCE)) {
            int firstNewline = text.indexOf('\n');
            text = firstNewline >= 0 ? text.substring(firstNewline + 1) : text.substring(3);
            int closingFence = text.lastIndexOf(MARKDOWN_FENCE);
            if (closingFence >= 0) {
                text = text.substring(0, closingFence);
            }
            text = text.strip();
        }

        // Ultimo recurso: recortar a la primera estructura JSON encontrada.
        int objectStart = text.indexOf('{');
        int arrayStart = text.indexOf('[');
        int start = (objectStart < 0)
                ? arrayStart
                : (arrayStart < 0 ? objectStart : Math.min(objectStart, arrayStart));
        if (start > 0) {
            char closing = text.charAt(start) == '{' ? '}' : ']';
            int end = text.lastIndexOf(closing);
            if (end > start) {
                text = text.substring(start, end + 1);
            }
        }

        return text.strip();
    }

    private String abbreviate(String text) {
        return text.length() <= 500 ? text : text.substring(0, 500) + "...";
    }
}
