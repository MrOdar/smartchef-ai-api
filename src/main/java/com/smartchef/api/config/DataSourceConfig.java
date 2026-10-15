package com.smartchef.api.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URISyntaxException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

/**
 * Configuracion resiliente del DataSource para despliegues en Render y entornos locales.
 *
 * <p>Si Render entrega un DATABASE_URL con formato postgres://user:pass@host:port/db,
 * lo traduce automaticamente al formato JDBC compatible (jdbc:postgresql://...).
 * Si no se define DATABASE_URL, cae al valor de {@code spring.datasource.url}
 * (H2 embebido en ./data), unica fuente de verdad de esa ruta.
 *
 * <p><strong>Aviso:</strong> el H2 de fichero no sobrevive a un redespliegue en
 * Render, que recrea el contenedor. Para datos persistentes hay que definir
 * DATABASE_URL apuntando a un PostgreSQL externo.
 */
@Configuration
@Profile("!test")
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    /** Ajustes de conexion ya resueltos, antes de abrir ningun socket. */
    public record JdbcSettings(String url, String username, String password, String driverClassName) {
    }

    @Bean
    @Primary
    public DataSource dataSource(
            @Value("${DATABASE_URL:}") String rawDatabaseUrl,
            @Value("${DATABASE_USERNAME:}") String username,
            @Value("${DATABASE_PASSWORD:}") String password,
            @Value("${spring.datasource.url}") String fallbackUrl) {

        JdbcSettings settings = resolveSettings(rawDatabaseUrl, username, password, fallbackUrl);

        HikariConfig config = new HikariConfig();
        config.setMaximumPoolSize(5);
        config.setConnectionTimeout(20000);
        config.setJdbcUrl(settings.url());
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        if (settings.driverClassName() != null) {
            config.setDriverClassName(settings.driverClassName());
        }

        return new HikariDataSource(config);
    }

    /**
     * Decide URL, credenciales y driver sin tocar la red. Se separa de la
     * creacion del pool a proposito: si el fallo de conexion se mezclara con el
     * fallo de parseo, un PostgreSQL caido al arrancar acabaria reintentando con
     * la URL postgres:// cruda, que el driver JDBC rechaza con un mensaje
     * enganoso en lugar del error real de conexion.
     */
    public JdbcSettings resolveSettings(String rawDatabaseUrl,
                                        String username,
                                        String password,
                                        String fallbackUrl) {

        if (!StringUtils.hasText(rawDatabaseUrl)) {
            // La ruta embebida vive solo en application.yml (spring.datasource.url)
            // para que no se desincronice de la que prepara el Dockerfile.
            log.warn("DATABASE_URL no configurado. Usando almacenamiento embebido H2 ({}). "
                    + "Este almacenamiento NO es persistente en Render: define DATABASE_URL "
                    + "con un PostgreSQL externo para no perder datos en cada despliegue.",
                    fallbackUrl);
            return new JdbcSettings(
                    fallbackUrl,
                    StringUtils.hasText(username) ? username : "sa",
                    password == null ? "" : password,
                    "org.h2.Driver");
        }

        String trimmed = rawDatabaseUrl.trim();

        // Soporte para formato nativo de Render (postgres://user:password@host:port/database)
        if (trimmed.startsWith("postgres://") || trimmed.startsWith("postgresql://")) {
            try {
                URI uri = new URI(trimmed);
                if (uri.getHost() == null) {
                    throw new URISyntaxException(trimmed, "sin host");
                }

                String dbUser = username;
                String dbPass = password;
                String userInfo = uri.getUserInfo();
                if (userInfo != null && userInfo.contains(":")) {
                    String[] parts = userInfo.split(":", 2);
                    dbUser = parts[0];
                    dbPass = parts[1];
                }

                int port = uri.getPort() > 0 ? uri.getPort() : 5432;
                String path = StringUtils.hasText(uri.getPath()) ? uri.getPath() : "/postgres";
                String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + path
                        + "?sslmode=" + sslModeFor(uri.getHost());

                log.info("PostgreSQL configurado en host: {}, database: {}", uri.getHost(), path);
                return new JdbcSettings(jdbcUrl, dbUser, dbPass, "org.postgresql.Driver");

            } catch (URISyntaxException e) {
                // Solo cubre el parseo: nunca enmascara un fallo de conexion.
                log.warn("DATABASE_URL no es una URI valida; se usara tal cual: {}", e.getMessage());
            }
        }

        // Si ya viene con prefijo jdbc:...
        String driver = null;
        if (trimmed.startsWith("jdbc:postgresql:")) {
            driver = "org.postgresql.Driver";
        } else if (trimmed.startsWith("jdbc:h2:")) {
            driver = "org.h2.Driver";
        }

        return new JdbcSettings(
                trimmed,
                StringUtils.hasText(username) ? username : null,
                StringUtils.hasText(password) ? password : null,
                driver);
    }

    /** En local no se exige TLS; fuera de localhost si. */
    private String sslModeFor(String host) {
        boolean local = "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host);
        return local ? "prefer" : "require";
    }
}
