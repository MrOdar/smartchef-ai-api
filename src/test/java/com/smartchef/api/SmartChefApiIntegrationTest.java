package com.smartchef.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartchef.api.exception.GeminiServiceException;
import com.smartchef.api.service.GeminiVisionService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pruebas de integracion sobre H2 en memoria con Gemini simulado mediante
 * {@code @MockBean}: se ejecutan 100% offline y sin API key real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SmartChefApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private GeminiVisionService geminiVisionService;

    private MockMultipartFile photo;

    @BeforeEach
    void setUp() throws Exception {
        photo = new MockMultipartFile(
                "image", "despensa.jpg", MediaType.IMAGE_JPEG_VALUE, TestDataFactory.smallJpeg());

        given(geminiVisionService.analyzeImage(any(), anyString()))
                .willReturn(TestDataFactory.sampleResult());
    }

    // --- Helpers -----------------------------------------------------------

    private String registerAndGetToken(String username, String email) throws Exception {
        String payload = """
                {
                  "username": "%s",
                  "email": "%s",
                  "password": "SuperSecreta123",
                  "fullName": "Chef de Pruebas"
                }
                """.formatted(username, email);

        String body = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.expiresInSeconds", is(86400)))
                .andExpect(jsonPath("$.user.username", is(username)))
                .andExpect(jsonPath("$.user.role", is("ROLE_USER")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        return objectMapper.readTree(body).path("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    // --- Salud y SPA -------------------------------------------------------

    @Test
    @DisplayName("La raiz redirige a la SPA y el HTML se sirve correctamente")
    void servesSinglePageApp() throws Exception {
        // La welcome page de Spring Boot hace forward interno a index.html.
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));

        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("SmartChef")))
                .andExpect(content().string(containsString("Analizando ingredientes con Gemini AI")));
    }

    @Test
    @DisplayName("Los recursos estaticos de la SPA son publicos")
    void servesStaticAssets() throws Exception {
        mockMvc.perform(get("/styles.css")).andExpect(status().isOk());
        mockMvc.perform(get("/app.js")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("La especificacion OpenAPI es publica")
    void exposesOpenApiSpec() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title", is("SmartChef AI API")))
                .andExpect(jsonPath("$.paths['/api/v1/recipes/generate-from-image']", notNullValue()));
    }

    @Test
    @DisplayName("Permite incrustar Swagger UI en un iframe del mismo origen")
    void allowsSameOriginFraming() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"));
    }

    // --- Autenticacion -----------------------------------------------------

    @Test
    @DisplayName("Registro, login con email y acceso al perfil")
    void registerThenLoginWithEmail() throws Exception {
        registerAndGetToken("chef_ana", "ana@smartchef.test");

        String loginBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "username": "ana@smartchef.test", "password": "SuperSecreta123" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        String token = objectMapper.readTree(loginBody).path("token").asText();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username", is("chef_ana")))
                .andExpect(jsonPath("$.email", is("ana@smartchef.test")));
    }

    @Test
    @DisplayName("Rechaza el registro con email invalido y contrasena corta")
    void rejectsInvalidRegistration() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "username": "x", "email": "no-es-email", "password": "123" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_ERROR")))
                .andExpect(jsonPath("$.fieldErrors.email", notNullValue()))
                .andExpect(jsonPath("$.fieldErrors.password", notNullValue()))
                .andExpect(jsonPath("$.fieldErrors.username", notNullValue()));
    }

    @Test
    @DisplayName("Rechaza usuarios duplicados con 409")
    void rejectsDuplicateUser() throws Exception {
        registerAndGetToken("chef_dup", "dup@smartchef.test");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "chef_dup",
                                  "email": "otro@smartchef.test",
                                  "password": "SuperSecreta123"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("DUPLICATE_RESOURCE")));
    }

    @Test
    @DisplayName("Credenciales incorrectas devuelven 401")
    void rejectsBadCredentials() throws Exception {
        registerAndGetToken("chef_bad", "bad@smartchef.test");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "username": "chef_bad", "password": "contrasena-equivocada" }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("INVALID_CREDENTIALS")));
    }

    // --- Generacion con IA -------------------------------------------------

    @Test
    @DisplayName("Genera 3 recetas desde una foto sin iniciar sesion")
    void generatesRecipesAnonymously() throws Exception {
        mockMvc.perform(multipart("/api/v1/recipes/generate-from-image").file(photo))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.scanSessionId", notNullValue()))
                .andExpect(jsonPath("$.detectedIngredients", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.recipes", hasSize(3)))
                .andExpect(jsonPath("$.recipes[0].title", is("Tortilla campesina de tomate")))
                .andExpect(jsonPath("$.recipes[0].difficulty", is("FACIL")))
                .andExpect(jsonPath("$.recipes[0].prepTimeMinutes", is(20)))
                .andExpect(jsonPath("$.recipes[0].caloriesEstimate", is(380)))
                .andExpect(jsonPath("$.recipes[0].steps", hasSize(3)))
                .andExpect(jsonPath("$.recipes[0].ingredients", hasSize(3)))
                .andExpect(jsonPath("$.recipes[0].ingredients[0].available", is(true)))
                .andExpect(jsonPath("$.recipes[0].ingredients[2].available", is(false)))
                .andExpect(jsonPath("$.recipes[1].difficulty", is("MEDIA")))
                .andExpect(jsonPath("$.recipes[2].difficulty", is("DIFICIL")));
    }

    @Test
    @DisplayName("Responde en UTF-8 limpio con acentos y enes")
    void respondsWithCleanUtf8() throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/recipes/generate-from-image").file(photo))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode root = objectMapper.readTree(body);
        assertStringContainsNoMojibake(root.toString());
    }

    private void assertStringContainsNoMojibake(String json) {
        org.assertj.core.api.Assertions.assertThat(json)
                .doesNotContain("�")
                .doesNotContain("Ã");
    }

    @Test
    @DisplayName("Rechaza un formato de archivo no soportado con 400")
    void rejectsUnsupportedFormat() throws Exception {
        MockMultipartFile pdf = new MockMultipartFile(
                "image", "menu.pdf", MediaType.APPLICATION_PDF_VALUE, new byte[] { 1, 2, 3 });

        mockMvc.perform(multipart("/api/v1/recipes/generate-from-image").file(pdf))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("INVALID_IMAGE")));
    }

    @Test
    @DisplayName("Rechaza la peticion sin el parametro image con 400")
    void rejectsMissingImageParameter() throws Exception {
        mockMvc.perform(multipart("/api/v1/recipes/generate-from-image"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("MISSING_PARAMETER")));
    }

    @Test
    @DisplayName("Devuelve 503 cuando la IA no encuentra alimentos")
    void returnsServiceUnavailableWhenNoFoodDetected() throws Exception {
        given(geminiVisionService.analyzeImage(any(), anyString()))
                .willReturn(TestDataFactory.emptyResult());

        mockMvc.perform(multipart("/api/v1/recipes/generate-from-image").file(photo))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error", is("AI_SERVICE_ERROR")))
                .andExpect(jsonPath("$.message", containsString("No se detectaron alimentos")));
    }

    @Test
    @DisplayName("Devuelve 503 cuando Gemini falla")
    void returnsServiceUnavailableWhenGeminiFails() throws Exception {
        given(geminiVisionService.analyzeImage(any(), anyString()))
                .willThrow(new GeminiServiceException("Gemini devolvio una respuesta vacia"));

        mockMvc.perform(multipart("/api/v1/recipes/generate-from-image").file(photo))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error", is("AI_SERVICE_ERROR")));
    }

    // --- Historial y favoritos --------------------------------------------

    @Test
    @DisplayName("El historial requiere JWT")
    void historyRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/recipes/history"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("UNAUTHORIZED")));
    }

    @Test
    @DisplayName("Los favoritos requieren JWT")
    void favoritesRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/recipes/favorites"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/recipes/1/favorite"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Un token invalido no concede acceso")
    void rejectsTamperedToken() throws Exception {
        mockMvc.perform(get("/api/v1/recipes/history")
                        .header("Authorization", bearer("token.falsificado.aqui")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("El escaneo autenticado queda guardado en el historial")
    void authenticatedScanAppearsInHistory() throws Exception {
        String token = registerAndGetToken("chef_hist", "hist@smartchef.test");

        mockMvc.perform(multipart("/api/v1/recipes/generate-from-image")
                        .file(photo)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/recipes/history").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].recipes", hasSize(3)))
                .andExpect(jsonPath("$[0].detectedIngredients", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$[0].createdAt", notNullValue()));
    }

    @Test
    @DisplayName("Alternar favorito guarda y elimina la receta")
    void togglesFavoriteBackAndForth() throws Exception {
        String token = registerAndGetToken("chef_fav", "fav@smartchef.test");

        String scanBody = mockMvc.perform(multipart("/api/v1/recipes/generate-from-image")
                        .file(photo)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        long recipeId = objectMapper.readTree(scanBody).at("/recipes/0/id").asLong();

        // Primera llamada: guarda en favoritos.
        mockMvc.perform(post("/api/v1/recipes/" + recipeId + "/favorite")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipeId", is((int) recipeId)))
                .andExpect(jsonPath("$.favorite", is(true)))
                .andExpect(jsonPath("$.message", containsString("guardada")));

        mockMvc.perform(get("/api/v1/recipes/favorites").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is((int) recipeId)))
                .andExpect(jsonPath("$[0].favorite", is(true)))
                .andExpect(jsonPath("$[0].steps", hasSize(3)))
                .andExpect(jsonPath("$[0].ingredients", hasSize(3)));

        // Segunda llamada: la elimina.
        mockMvc.perform(post("/api/v1/recipes/" + recipeId + "/favorite")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorite", is(false)))
                .andExpect(jsonPath("$.message", containsString("eliminada")));

        mockMvc.perform(get("/api/v1/recipes/favorites").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("Marcar como favorita una receta inexistente devuelve 404")
    void favoriteOnMissingRecipeReturnsNotFound() throws Exception {
        String token = registerAndGetToken("chef_404", "notfound@smartchef.test");

        mockMvc.perform(post("/api/v1/recipes/999999/favorite")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("NOT_FOUND")));
    }

    @Test
    @DisplayName("Cada usuario solo ve su propio historial")
    void historyIsScopedPerUser() throws Exception {
        String tokenA = registerAndGetToken("chef_a", "a@smartchef.test");
        String tokenB = registerAndGetToken("chef_b", "b@smartchef.test");

        mockMvc.perform(multipart("/api/v1/recipes/generate-from-image")
                        .file(photo)
                        .header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/recipes/history").header("Authorization", bearer(tokenA)))
                .andExpect(jsonPath("$", hasSize(1)));

        mockMvc.perform(get("/api/v1/recipes/history").header("Authorization", bearer(tokenB)))
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
