package com.smartchef.api.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
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
 * Si no se define DATABASE_URL, inicializa un almacenamiento embebido H2 en /tmp/smartchef
 * para operar 100% gratis y sin dependencias externas.
 */
@Configuration
@Profile("!test")
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    private static final String DEFAULT_H2_URL =
            "jdbc:h2:file:/tmp/smartchef;DB_CLOSE_DELAY=-1";

    @Bean
    @Primary
    public DataSource dataSource(
            @Value("${DATABASE_URL:}") String rawDatabaseUrl,
            @Value("${DATABASE_USERNAME:}") String username,
            @Value("${DATABASE_PASSWORD:}") String password) {

        HikariConfig config = new HikariConfig();
        config.setMaximumPoolSize(5);
        config.setConnectionTimeout(20000);

        if (!StringUtils.hasText(rawDatabaseUrl)) {
            log.info("DATABASE_URL no configurado. Utilizando almacenamiento local embebido H2 (/tmp/smartchef)");
            config.setJdbcUrl(DEFAULT_H2_URL);
            config.setDriverClassName("org.h2.Driver");
            config.setUsername("sa");
            config.setPassword("");
            return new HikariDataSource(config);
        }

        String trimmed = rawDatabaseUrl.trim();

        // Soporte para formato nativo de Render (postgres://user:password@host:port/database)
        if (trimmed.startsWith("postgres://") || trimmed.startsWith("postgresql://")) {
            try {
                URI uri = new URI(trimmed);
                String userInfo = uri.getUserInfo();
                String dbUser = username;
                String dbPass = password;

                if (userInfo != null && userInfo.contains(":")) {
                    String[] parts = userInfo.split(":", 2);
                    dbUser = parts[0];
                    dbPass = parts[1];
                }

                int port = uri.getPort() > 0 ? uri.getPort() : 5432;
                String path = uri.getPath();
                String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + path + "?sslmode=require";

                log.info("Conectando a PostgreSQL en Render host: {}, database: {}", uri.getHost(), path);
                config.setJdbcUrl(jdbcUrl);
                config.setUsername(dbUser);
                config.setPassword(dbPass);
                config.setDriverClassName("org.postgresql.Driver");
                return new HikariDataSource(config);
            } catch (Exception e) {
                log.warn("Fallo al parsear DATABASE_URL como URI; intentando como JDBC directo: {}", e.getMessage());
            }
        }

        // Si ya viene con prefijo jdbc:...
        config.setJdbcUrl(trimmed);
        if (StringUtils.hasText(username)) {
            config.setUsername(username);
        }
        if (StringUtils.hasText(password)) {
            config.setPassword(password);
        }
        if (trimmed.startsWith("jdbc:postgresql:")) {
            config.setDriverClassName("org.postgresql.Driver");
        } else if (trimmed.startsWith("jdbc:h2:")) {
            config.setDriverClassName("org.h2.Driver");
        }

        return new HikariDataSource(config);
    }
}
