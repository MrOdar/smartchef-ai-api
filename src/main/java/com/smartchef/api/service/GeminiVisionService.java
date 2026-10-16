package com.smartchef.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartchef.api.config.GeminiProperties;
import com.smartchef.api.dto.GeminiRecipeResult;
import com.smartchef.api.exception.GeminiServiceException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
                  "description": "Descripcion gastronomica apetitosa de 2 o 3 oraciones completas que
                                  explique el caracter del plato, su textura y a que ocasion encaja",
                  "prep_time_minutes": 25,
                  "difficulty": "FACIL",
                  "calories_estimate": 450,
                  "steps": [
                    "Paso 1 con tecnica, tiempo y temperatura concretos",
                    "Paso 2 con tecnica, tiempo y temperatura concretos",
                    "Paso 3 con tecnica, tiempo y temperatura concretos",
                    "Paso 4 con tecnica, tiempo y temperatura concretos"
                  ],
                  "chef_tip": "Consejo practico del chef para mejorar notablemente el plato",
                  "ingredients": [
                    { "name": "tomate", "quantity": "2 unidades medianas", "is_available": true },
                    { "name": "queso parmesano", "quantity": "50 g rallado", "is_available": false }
                  ]
                }
              ]
            }

            Reglas obligatorias:
            - El array "recipes" debe contener exactamente 3 recetas.
            - "difficulty" solo puede ser FACIL, MEDIA o DIFICIL.
            - "prep_time_minutes" y "calories_estimate" son numeros enteros.
            - "is_available" es true solo si el ingrediente aparece en la imagen; false si hay que comprarlo.
            - Todos los textos en espanol neutro.
            - Si la imagen no contiene alimentos, devuelve "detected_ingredients" vacio y "recipes" vacio.

            Calidad exigida en cada receta:
            - "description": entre 2 y 3 oraciones completas, apetitosas y concretas. Nunca una sola frase suelta.
            - "steps": entre 4 y 6 pasos. Cada paso debe ser una instruccion accionable que indique el
              tiempo de coccion, la temperatura o el tipo de fuego (suave, medio, fuerte) y la tecnica
              culinaria empleada (sofreir, pochar, sellar, emulsionar, gratinar, reducir...).
              No escribas pasos vagos del tipo "cocinar hasta que este listo".
            - "ingredients": cantidades precisas y utiles (gramos, mililitros, unidades o cucharadas).
              Evita "al gusto" salvo en sal, pimienta y especias.
            - "chef_tip": obligatorio en las 3 recetas. Una sola frase con un consejo practico y
              especifico de este plato (un truco de textura, de punto de coccion, de sustitucion o de
              presentacion). No repitas informacion que ya este en los pasos.

            Se generoso en el detalle: las recetas deben poder seguirse sin conocimientos previos.
            """;

    /**
     * Orden de preferencia. 3.8-flash va primero porque las recetas extensas con
     * "chef_tip" exigen mas capacidad de la que ofrece el escalon Lite, que
     * tiende a truncar o a desviarse del esquema JSON. Lite queda de respaldo
     * inmediato para cuando 3.8 este saturado.
     */
    private static final List<String> FALLBACK_MODELS = List.of(
            "gemini-3.8-flash",
            "gemini-3.5-flash-lite",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.0-flash",
            "gemini-1.5-flash",
            "gemini-1.5-flash-latest"
    );

    private final RestTemplate restTemplate;
    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;

    /** Tiempo de espera antes de reintentar un descubrimiento que fallo. */
    private static final long DISCOVERY_RETRY_COOLDOWN_MS = 300_000L;

    private volatile List<String> discoveredModels = List.of();
    private volatile String effectiveBaseUrl = null;
    private volatile long lastDiscoveryFailureAt = 0L;

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

        StringBuilder discoveryLog = new StringBuilder();
        List<String> dynamicModels = discoverAvailableModels(discoveryLog);
        List<String> candidateModels = buildCandidateList(dynamicModels);

        String requestBody = buildRequestBody(imageBytes, mimeType);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
            headers.set("x-goog-api-key", properties.apiKey());
        }
        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

        String base = (effectiveBaseUrl != null) ? effectiveBaseUrl : properties.baseUrl();
        String rawResponse = null;
        Exception lastException = null;

        for (int i = 0; i < candidateModels.size(); i++) {
            String targetModel = candidateModels.get(i);
            String url = "%s/models/%s:generateContent?key=%s".formatted(base, targetModel, properties.apiKey());
            try {
                log.info("Invocando Gemini AI ({}) con modelo: {}", base, targetModel);
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
                String detail = extractGoogleErrorMessage(e.getResponseBodyAsString());
                throw new GeminiServiceException(
                        "No se pudo contactar al servicio de IA de Gemini (%s): %s".formatted(
                                targetModel, detail != null ? detail : e.getMessage()), e);
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
                String detail = (lastException instanceof HttpStatusCodeException httpEx)
                        ? extractGoogleErrorMessage(httpEx.getResponseBodyAsString())
                        : lastException.getMessage();
                throw new GeminiServiceException(
                        "No se pudo contactar al servicio de IA de Gemini: " + detail, lastException);
            }
            throw new GeminiServiceException("Gemini devolvio una respuesta vacia");
        }

        return parseRecipeResult(extractModelText(rawResponse));
    }

    /**
     * Lista los modelos con generateContent que admite la API key. El resultado
     * se cachea, y el fallo tambien: si el descubrimiento no encuentra nada, no
     * se reintenta hasta que pase {@link #DISCOVERY_RETRY_COOLDOWN_MS}, para no
     * anadir dos llamadas salientes a cada foto cuando Google esta degradado.
     */
    public List<String> discoverAvailableModels(StringBuilder logOut) {
        List<String> cached = discoveredModels;
        if (!cached.isEmpty()) {
            return new ArrayList<>(cached);
        }
        if (!properties.hasApiKey()) {
            return List.of();
        }
        if (inDiscoveryCooldown()) {
            log.debug("Descubrimiento de modelos en pausa tras un fallo reciente; se usa la lista de respaldo");
            if (logOut != null) {
                logOut.append("Descubrimiento en pausa tras un fallo reciente.\n");
            }
            return List.of();
        }
        return runDiscovery(logOut);
    }

    private boolean inDiscoveryCooldown() {
        long failedAt = lastDiscoveryFailureAt;
        return failedAt > 0
                && (System.currentTimeMillis() - failedAt) < DISCOVERY_RETRY_COOLDOWN_MS;
    }

    private synchronized List<String> runDiscovery(StringBuilder logOut) {
        // Otro hilo pudo completar el descubrimiento mientras esperabamos el lock.
        List<String> cached = discoveredModels;
        if (!cached.isEmpty()) {
            return new ArrayList<>(cached);
        }
        if (inDiscoveryCooldown()) {
            return List.of();
        }

        List<String> baseUrls = List.of(
                properties.baseUrl(),
                "https://generativelanguage.googleapis.com/v1beta",
                "https://generativelanguage.googleapis.com/v1"
        ).stream().distinct().toList();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("x-goog-api-key", properties.apiKey());
        HttpEntity<?> req = new HttpEntity<>(headers);

        List<String> found = new ArrayList<>();
        String workingBase = null;

        for (String base : baseUrls) {
            String listUrl = "%s/models?key=%s".formatted(base, properties.apiKey());
            try {
                ResponseEntity<String> resp = restTemplate.exchange(listUrl, HttpMethod.GET, req, String.class);
                if (resp.getBody() != null && !resp.getBody().isBlank()) {
                    JsonNode root = objectMapper.readTree(resp.getBody());
                    JsonNode models = root.path("models");
                    if (models.isArray()) {
                        for (JsonNode m : models) {
                            JsonNode methods = m.path("supportedGenerationMethods");
                            boolean supportsGen = false;
                            if (methods.isArray()) {
                                for (JsonNode method : methods) {
                                    if ("generateContent".equalsIgnoreCase(method.asText())) {
                                        supportsGen = true;
                                        break;
                                    }
                                }
                            }
                            if (supportsGen) {
                                String name = m.path("name").asText();
                                if (name.startsWith("models/")) {
                                    name = name.substring("models/".length());
                                }
                                found.add(name);
                            }
                        }
                    }
                    if (!found.isEmpty()) {
                        workingBase = base;
                        if (logOut != null) {
                            logOut.append("Descubiertos en ").append(base).append(": ").append(found).append("\n");
                        }
                        log.info("Modelos descubiertos con generateContent en {}: {}", base, found);
                        break;
                    }
                }
            } catch (HttpStatusCodeException e) {
                // El resumen solo lleva el estado: el cuerpo de Google puede
                // contener detalles de la cuenta y no debe salir en la respuesta.
                if (logOut != null) {
                    logOut.append(base).append(" -> HTTP ").append(e.getStatusCode().value()).append("\n");
                }
                log.warn("Fallo al listar modelos desde {}: HTTP {} - {}",
                        base, e.getStatusCode(), e.getResponseBodyAsString());
            } catch (Exception e) {
                if (logOut != null) {
                    logOut.append(base).append(" -> sin respuesta utilizable\n");
                }
                log.warn("Error al contactar {}: {}", base, e.getMessage());
            }
        }

        if (!found.isEmpty()) {
            this.effectiveBaseUrl = workingBase;
            this.discoveredModels = found;
            this.lastDiscoveryFailureAt = 0L;
            return new ArrayList<>(found);
        }

        // Se recuerda el fallo para no repetir el descubrimiento en cada escaneo.
        this.lastDiscoveryFailureAt = System.currentTimeMillis();
        log.warn("No se pudo descubrir ningun modelo. Se usara la lista de respaldo y no se reintentara durante {} s",
                DISCOVERY_RETRY_COOLDOWN_MS / 1000);
        return List.of();
    }

    /**
     * Comprueba la conectividad con Gemini forzando un redescubrimiento. Solo se
     * expone a administradores y no devuelve cuerpos de error crudos de Google:
     * el resumen se limita al base URL y al codigo de estado.
     */
    public Map<String, Object> diagnose() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hasApiKey", properties.hasApiKey());
        if (!properties.hasApiKey()) {
            result.put("status", "ERROR");
            result.put("message", "GEMINI_API_KEY no esta configurada");
            return result;
        }

        result.put("configuredModel", properties.model());

        StringBuilder summary = new StringBuilder();
        List<String> found = refreshDiscovery(summary);

        result.put("discoveredModels", found);
        result.put("effectiveBaseUrl", effectiveBaseUrl != null ? effectiveBaseUrl : properties.baseUrl());
        result.put("attempts", summary.toString().lines().toList());
        result.put("status", found.isEmpty() ? "WARNING" : "OK");
        return result;
    }

    /** Invalida la cache y vuelve a descubrir, ignorando el periodo de espera. */
    private synchronized List<String> refreshDiscovery(StringBuilder summary) {
        this.discoveredModels = List.of();
        this.lastDiscoveryFailureAt = 0L;
        return runDiscovery(summary);
    }

    private List<String> buildCandidateList(List<String> dynamicModels) {
        List<String> candidates = new ArrayList<>();
        if (properties.model() != null && !properties.model().isBlank()) {
            candidates.add(properties.model().trim());
        }
        for (String dyn : dynamicModels) {
            if (!candidates.contains(dyn)) {
                candidates.add(dyn);
            }
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
            int code = statusEx.getStatusCode().value();
            if (code == 404 || code == 429 || code == 503 || code == 500 || code == 502) {
                return true;
            }
        }
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        String lower = msg.toLowerCase();
        return lower.contains("404") || lower.contains("not found") || lower.contains("not supported")
                || lower.contains("429") || lower.contains("high demand") || lower.contains("quota")
                || lower.contains("rate limit") || lower.contains("overloaded") || lower.contains("503");
    }

    private boolean isModelNotFoundError(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            JsonNode error = root.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                int code = error.path("code").asInt(0);
                String msg = error.path("message").asText("").toLowerCase();
                String status = error.path("status").asText("").toLowerCase();
                return code == 404 || code == 429 || code == 503
                        || status.contains("not_found") || status.contains("resource_exhausted") || status.contains("unavailable")
                        || msg.contains("not found") || msg.contains("not supported")
                        || msg.contains("high demand") || msg.contains("quota") || msg.contains("rate limit");
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private String extractGoogleErrorMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode messageNode = root.at("/error/message");
            if (!messageNode.isMissingNode() && !messageNode.isNull()) {
                return messageNode.asText();
            }
        } catch (Exception ignored) {
        }
        return responseBody;
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
        // 8192: las 3 recetas extensas con pasos detallados y chef_tip no caben
        // en 4096, y un JSON truncado revienta el parseo de forma determinista.
        generationConfig.put("maxOutputTokens", 8192);

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
