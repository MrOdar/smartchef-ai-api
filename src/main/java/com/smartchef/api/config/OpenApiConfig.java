package com.smartchef.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI smartChefOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SmartChef AI API")
                        .version("1.0.0")
                        .description("""
                                API REST que analiza fotos de despensas o refrigeradoras con Google Gemini 1.5 Flash
                                para detectar ingredientes y generar 3 recetas de aprovechamiento personalizadas.

                                Los endpoints de historial y favoritos requieren un token JWT obtenido en
                                `POST /api/v1/auth/login`. Usa el boton **Authorize** e introduce el token.
                                """)
                        .contact(new Contact().name("SmartChef AI"))
                        .license(new License().name("MIT")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Token JWT valido por 24 horas")));
    }
}
