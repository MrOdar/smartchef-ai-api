package com.smartchef.api.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * IP del cliente para los frenos.
 *
 * <p>Render publica el servicio detras de Cloudflare, que escribe la IP real en
 * {@code CF-Connecting-IP} y pisa cualquier valor que mande el cliente. No se
 * usa la primera entrada de {@code X-Forwarded-For}: esa la elige el cliente y
 * bastaria con inventarla para estrenar cupo en cada peticion. Si la cabecera
 * configurada falta (desarrollo local), se usa la direccion del socket.
 */
public final class ClientIpResolver {

    private final String header;

    public ClientIpResolver(String header) {
        this.header = header;
    }

    public String resolve(HttpServletRequest request) {
        if (StringUtils.hasText(header)) {
            String value = request.getHeader(header);
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return request.getRemoteAddr();
    }
}
