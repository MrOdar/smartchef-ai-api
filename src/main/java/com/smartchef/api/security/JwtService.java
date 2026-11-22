package com.smartchef.api.security;

import com.smartchef.api.config.JwtProperties;
import com.smartchef.api.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.DecodingException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Emision y validacion de tokens JWT con vigencia de 24 horas. El algoritmo HMAC
 * lo elige jjwt segun la longitud de la clave (HS256 con 32 bytes, HS512 con 64).
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final JwtProperties properties;
    private final SecretKey signingKey;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.signingKey = buildSigningKey(properties.secret());
    }

    public String generateToken(User user) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + properties.expirationMs());

        return Jwts.builder()
                .subject(user.getUsername())
                .claim("uid", user.getId())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(now)
                .expiration(expiration)
                .signWith(signingKey)
                .compact();
    }

    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    public boolean isTokenValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Token JWT invalido: {}", e.getMessage());
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** HS256 exige al menos 256 bits de clave (RFC 7518, seccion 3.2). */
    private static final int MIN_SECRET_BYTES = 32;

    /**
     * SHA-256 del secreto que estuvo publicado como valor por defecto en
     * application.yml y .env.example. Cualquiera puede firmar tokens con el, asi
     * que se rechaza aunque alguien lo copie a una variable de entorno.
     */
    private static final String PUBLISHED_DEFAULT_SHA256 =
            "a7f65ec15a6e9dd98827bc84d5745c4459779f8d2ec106caa16763ac5cad10c4";

    /**
     * Acepta el secreto en Base64 (recomendado, al menos 32 bytes decodificados).
     * Si no es Base64, deriva la clave con SHA-256 de un texto de al menos 32
     * bytes. Sin secreto, o con uno corto o publicado, la aplicacion no arranca.
     */
    private static SecretKey buildSigningKey(String secret) {
        if (secret == null || secret.isBlank() || secret.startsWith("${")) {
            throw new IllegalStateException(
                    "Falta la variable de entorno JWT_SECRET. Genera una con: openssl rand -base64 48");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET debe tener al menos " + MIN_SECRET_BYTES + " bytes.");
        }
        if (PUBLISHED_DEFAULT_SHA256.equals(sha256Hex(secret))) {
            throw new IllegalStateException(
                    "JWT_SECRET es el valor de ejemplo publicado en el repositorio. Genera uno nuevo.");
        }
        try {
            byte[] decoded = Decoders.BASE64.decode(secret);
            if (decoded.length >= 32) {
                return Keys.hmacShaKeyFor(decoded);
            }
        } catch (DecodingException | IllegalArgumentException ignored) {
            // No era Base64 valido: se deriva la clave mas abajo.
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            return Keys.hmacShaKeyFor(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible en esta JVM", e);
        }
    }

    private static String sha256Hex(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible en esta JVM", e);
        }
    }
}
