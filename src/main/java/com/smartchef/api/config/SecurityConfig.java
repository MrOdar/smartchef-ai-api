package com.smartchef.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartchef.api.dto.ApiErrorResponse;
import com.smartchef.api.security.JwtAuthenticationFilter;
import com.smartchef.api.security.SmartChefUserDetailsService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer.FrameOptionsConfig;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SmartChefUserDetailsService userDetailsService;
    private final ObjectMapper objectMapper;
    private final List<String> corsAllowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          SmartChefUserDetailsService userDetailsService,
                          ObjectMapper objectMapper,
                          @Value("${smartchef.cors.allowed-origins:}") List<String> corsAllowedOrigins) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.userDetailsService = userDetailsService;
        this.objectMapper = objectMapper;
        this.corsAllowedOrigins = corsAllowedOrigins.stream()
                .map(String::trim).filter(origin -> !origin.isEmpty()).toList();
    }

    /**
     * La SPA, la landing y Swagger UI se sirven desde este mismo origen. Sin
     * scripts inline ni de terceros: un XSS no podria cargar codigo ajeno ni
     * leer el token de localStorage para mandarlo fuera ({@code connect-src}).
     * Google Fonts es el unico tercero, solo para hojas de estilo y fuentes.
     */
    private static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
            "font-src 'self' https://fonts.gstatic.com",
            "img-src 'self' data:",
            "connect-src 'self'",
            "frame-src 'self'",
            "frame-ancestors 'self'",
            "form-action 'self'",
            "base-uri 'self'",
            "object-src 'none'");

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // La SPA incrusta Swagger UI en un iframe del mismo origen.
                .headers(headers -> headers
                        .frameOptions(FrameOptionsConfig::sameOrigin)
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicy(permissions -> permissions.policy(
                                "camera=(), microphone=(), geolocation=(), payment=()")))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // SPA y recursos estaticos
                        .requestMatchers("/", "/index.html", "/app.html", "/styles.css", "/app.js",
                                "/favicon.ico", "/assets/**").permitAll()
                        // Documentacion
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        // Autenticacion
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // El escaneo y la demo funcionan sin login (user_id nullable)
                        .requestMatchers("/api/v1/recipes/generate-from-image",
                                "/api/v1/recipes/from-ingredients",
                                "/api/v1/recipes/demo").permitAll()
                        // El diagnostico consulta la API de Google y revela
                        // configuracion: solo para administradores.
                        .requestMatchers("/api/v1/recipes/diagnostics")
                                .hasRole("ADMIN")
                        // Historial y favoritos requieren JWT
                        .requestMatchers("/api/v1/recipes/history",
                                "/api/v1/recipes/favorites").authenticated()
                        .requestMatchers("/api/v1/recipes/*/favorite").authenticated()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(unauthorizedEntryPoint())
                        .accessDeniedHandler(forbiddenHandler()))
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthenticationFilter,
                        org.springframework.security.web.authentication
                                .UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(DaoAuthenticationProvider provider) {
        return provider::authenticate;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        // La SPA es del mismo origen y no necesita CORS. Solo se abren los
        // origenes listados en CORS_ALLOWED_ORIGINS; sin lista, ninguno.
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsAllowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    /** Devuelve 403 en JSON UTF-8 cuando falta el rol necesario. */
    private AccessDeniedHandler forbiddenHandler() {
        return (request, response, accessDeniedException) -> {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getWriter(), ApiErrorResponse.of(
                    HttpStatus.FORBIDDEN.value(),
                    "FORBIDDEN",
                    "No tienes permisos suficientes para acceder a este recurso"));
        };
    }

    /** Devuelve 401 en JSON UTF-8 en lugar de una redireccion HTML. */
    private AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getWriter(), ApiErrorResponse.of(
                    HttpStatus.UNAUTHORIZED.value(),
                    "UNAUTHORIZED",
                    "Necesitas iniciar sesion para acceder a este recurso"));
        };
    }
}
