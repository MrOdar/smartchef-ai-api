package com.smartchef.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartchef.api.exception.GeminiServiceException;
import com.smartchef.api.service.GeminiVisionService;
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
import org.springframework.web.client.ResourceAccessException;

/** Frenos, cabeceras y errores que no filtran configuracion. */
@SpringBootTest(properties = {
        "smartchef.rate-limit.enabled=true",
        "smartchef.rate-limit.ai-per-ip.max=2",
        "smartchef.rate-limit.ai-global.max=1000",
        "smartchef.rate-limit.login-per-ip.max=1000",
        "smartchef.rate-limit.login-failures-per-account.max=2",
        "smartchef.cors.allowed-origins=https://permitido.example"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityHardeningIntegrationTest {

    private static final String IP_HEADER = "CF-Connecting-IP";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GeminiVisionService geminiVisionService;

    private MockMultipartFile photo() throws Exception {
        return new MockMultipartFile("image", "despensa.jpg", MediaType.IMAGE_JPEG_VALUE,
                TestDataFactory.smallJpeg());
    }

    @Test
    @DisplayName("Frena el escaneo anonimo por IP con 429 y Retry-After")
    void limitsAiPerIp() throws Exception {
        given(geminiVisionService.analyzeImage(any(), anyString())).willReturn(TestDataFactory.sampleResult());

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(multipart("/api/v1/recipes/generate-from-image").file(photo())
                            .header(IP_HEADER, "203.0.113.10"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(multipart("/api/v1/recipes/generate-from-image").file(photo())
                        .header(IP_HEADER, "203.0.113.10"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error", is("TOO_MANY_REQUESTS")));

        // Otra IP conserva su cupo.
        mockMvc.perform(multipart("/api/v1/recipes/generate-from-image").file(photo())
                        .header(IP_HEADER, "203.0.113.11"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Bloquea una cuenta tras varios fallos de login")
    void locksAccountAfterFailures() throws Exception {
        String body = """
                { "username": "victima@smartchef.test", "password": "intento-fallido" }
                """;
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)
                            .header(IP_HEADER, "198.51.100." + i))
                    .andExpect(status().isUnauthorized());
        }
        // Cambiar de IP no sirve: el bloqueo es por cuenta.
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(IP_HEADER, "198.51.100.99"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("Un fallo de red con Gemini no devuelve el detalle al cliente")
    void hidesGeminiFailureDetails() throws Exception {
        given(geminiVisionService.analyzeImage(any(), anyString())).willThrow(new GeminiServiceException(
                "No se pudo contactar al servicio de IA de Gemini: I/O error for https://x/models?key=SECRETA",
                new ResourceAccessException("timeout")));

        mockMvc.perform(multipart("/api/v1/recipes/generate-from-image").file(photo())
                        .header(IP_HEADER, "203.0.113.50"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message", not(containsString("SECRETA"))))
                .andExpect(jsonPath("$.message", not(containsString("http"))));
    }

    @Test
    @DisplayName("Envia CSP y no abre CORS a origenes no listados")
    void sendsCspAndRestrictsCors() throws Exception {
        mockMvc.perform(get("/app.html"))
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'self'")));

        mockMvc.perform(options("/api/v1/recipes/demo")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());

        mockMvc.perform(options("/api/v1/recipes/demo")
                        .header("Origin", "https://permitido.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().string("Access-Control-Allow-Origin", "https://permitido.example"));
    }
}
