package com.smartchef.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartchef.api.config.JwtProperties;
import com.smartchef.api.entity.Role;
import com.smartchef.api.entity.User;
import com.smartchef.api.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String BASE64_SECRET =
            "dGVzdC1vbmx5LXNlY3JldC1rZXktZm9yLXNtYXJ0Y2hlZi1pbnRlZ3JhdGlvbi10ZXN0cy1oczI1Ni02NC1ieXRlcy1sb25n";

    private User user() {
        User user = new User();
        user.setId(7L);
        user.setUsername("chef_test");
        user.setEmail("chef@smartchef.test");
        user.setRole(Role.ROLE_USER);
        return user;
    }

    @Test
    @DisplayName("Emite un token valido de 24 horas y recupera el username")
    void generatesAndValidatesToken() {
        JwtService service = new JwtService(new JwtProperties(BASE64_SECRET, 86_400_000L));

        String token = service.generateToken(user());

        assertThat(token).isNotBlank();
        assertThat(service.isTokenValid(token)).isTrue();
        assertThat(service.extractUsername(token)).isEqualTo("chef_test");
    }

    @Test
    @DisplayName("La vigencia por defecto es de 86400 segundos")
    void exposesExpirationInSeconds() {
        assertThat(new JwtProperties(BASE64_SECRET, 86_400_000L).expirationSeconds())
                .isEqualTo(86_400L);
    }

    @Test
    @DisplayName("Rechaza un token manipulado o con otra firma")
    void rejectsTamperedToken() {
        JwtService service = new JwtService(new JwtProperties(BASE64_SECRET, 86_400_000L));
        String token = service.generateToken(user());

        assertThat(service.isTokenValid(token + "x")).isFalse();
        assertThat(service.isTokenValid("no.es.un.jwt")).isFalse();

        JwtService otherKey = new JwtService(new JwtProperties(
                "b3Ryby1zZWNyZXRvLWRpc3RpbnRvLXBhcmEtc21hcnRjaGVmLWNvbi1sb25naXR1ZC1zdWZpY2llbnRl",
                86_400_000L));
        assertThat(otherKey.isTokenValid(token)).isFalse();
    }

    @Test
    @DisplayName("Rechaza un token ya expirado")
    void rejectsExpiredToken() throws InterruptedException {
        JwtService service = new JwtService(new JwtProperties(BASE64_SECRET, 1L));
        String token = service.generateToken(user());

        Thread.sleep(1100);

        assertThat(service.isTokenValid(token)).isFalse();
    }

    @Test
    @DisplayName("Deriva una clave segura cuando el secreto no es Base64")
    void derivesKeyFromPlainSecret() {
        JwtService service = new JwtService(
                new JwtProperties("secreto-en-texto-plano-del-entorno", 86_400_000L));

        String token = service.generateToken(user());

        assertThat(service.isTokenValid(token)).isTrue();
        assertThat(service.extractUsername(token)).isEqualTo("chef_test");
    }
}
