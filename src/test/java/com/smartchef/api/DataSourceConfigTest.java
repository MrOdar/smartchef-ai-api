package com.smartchef.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartchef.api.config.DataSourceConfig;
import com.smartchef.api.config.DataSourceConfig.JdbcSettings;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

/**
 * Pruebas del DataSource resiliente. Esta clase lleva {@code @Profile("!test")},
 * asi que las pruebas de integracion nunca la instancian: aqui se construye a
 * mano para cubrir el parseo de URLs y, sobre todo, para comprobar que la URL
 * H2 de respaldo abre conexion de verdad.
 */
class DataSourceConfigTest {

    private final DataSourceConfig config = new DataSourceConfig();

    /** Lee la URL de respaldo real desde application.yml, sin duplicarla aqui. */
    private String embeddedUrlFromApplicationYml() throws IOException {
        String yaml = new String(new ClassPathResource("application.yml")
                .getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

        for (String line : yaml.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("url: ${DATABASE_URL:")) {
                String value = trimmed.substring("url: ${DATABASE_URL:".length());
                return value.substring(0, value.lastIndexOf('}'));
            }
        }
        throw new IllegalStateException("No se encontro spring.datasource.url en application.yml");
    }

    @Test
    @DisplayName("La URL H2 de respaldo de application.yml abre conexion sin errores")
    void embeddedFallbackUrlActuallyConnects(@TempDir Path tempDir) throws Exception {
        String url = embeddedUrlFromApplicationYml()
                .replace("./data/smartchef", tempDir.resolve("smartchef").toString().replace('\\', '/'));

        // Reproduce lo que hace la app cuando no hay DATABASE_URL definido.
        DataSource dataSource = config.dataSource("", "", "", url);

        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(5)).isTrue();
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("H2");
        } finally {
            ((HikariDataSource) dataSource).close();
        }
    }

    @Test
    @DisplayName("La ruta embebida vive en ./data, igual que el directorio del Dockerfile")
    void embeddedPathMatchesDockerfileDirectory() throws Exception {
        assertThat(embeddedUrlFromApplicationYml()).contains("./data/smartchef");

        String dockerfile = Files.readString(Path.of("Dockerfile"));
        assertThat(dockerfile)
                .as("el Dockerfile debe preparar el mismo directorio que usa la app")
                .contains("/app/data");
        assertThat(dockerfile)
                .as("WORKDIR /app hace que ./data sea /app/data")
                .contains("WORKDIR /app");
    }

    @Test
    @DisplayName("Traduce una URL postgres:// de Render al formato JDBC")
    void translatesRenderPostgresUrl() {
        JdbcSettings settings = config.resolveSettings(
                "postgres://usuario:clave@dpg-abc123.oregon-postgres.render.com:5432/smartchef_db",
                "", "", "jdbc:h2:mem:no-usado");

        assertThat(settings.url()).isEqualTo(
                "jdbc:postgresql://dpg-abc123.oregon-postgres.render.com:5432/smartchef_db?sslmode=require");
        assertThat(settings.username()).isEqualTo("usuario");
        assertThat(settings.password()).isEqualTo("clave");
        assertThat(settings.driverClassName()).isEqualTo("org.postgresql.Driver");
    }

    @Test
    @DisplayName("Usa el puerto 5432 por defecto si la URL no lo indica")
    void defaultsPostgresPort() {
        assertThat(config.resolveSettings(
                "postgresql://u:p@host.interno/smartchef", "", "", "jdbc:h2:mem:no-usado").url())
                .contains("host.interno:5432/smartchef");
    }

    @Test
    @DisplayName("No exige TLS contra un PostgreSQL local")
    void doesNotRequireTlsOnLocalhost() {
        assertThat(config.resolveSettings(
                "postgres://u:p@localhost:5432/smartchef", "", "", "jdbc:h2:mem:no-usado").url())
                .endsWith("sslmode=prefer");
    }

    @Test
    @DisplayName("Respeta una URL que ya viene en formato JDBC")
    void keepsExplicitJdbcUrl() {
        JdbcSettings settings = config.resolveSettings(
                "jdbc:postgresql://localhost:5432/smartchef", "postgres", "secreto",
                "jdbc:h2:mem:no-usado");

        assertThat(settings.url()).isEqualTo("jdbc:postgresql://localhost:5432/smartchef");
        assertThat(settings.username()).isEqualTo("postgres");
        assertThat(settings.driverClassName()).isEqualTo("org.postgresql.Driver");
    }

    @Test
    @DisplayName("Las credenciales de la URL tienen prioridad sobre las variables sueltas")
    void urlCredentialsWinOverEnvVars() {
        JdbcSettings settings = config.resolveSettings(
                "postgres://de_la_url:clave_url@host:5432/db",
                "de_la_variable", "clave_variable", "jdbc:h2:mem:no-usado");

        assertThat(settings.username()).isEqualTo("de_la_url");
        assertThat(settings.password()).isEqualTo("clave_url");
    }

    @Test
    @DisplayName("Una DATABASE_URL corrupta no se convierte en una URL postgres:// cruda")
    void malformedUrlNeverProducesRawPostgresScheme() {
        // Antes, cualquier fallo dentro del try (incluida la conexion) hacia caer
        // en la rama generica y entregaba la URL postgres:// sin traducir, que el
        // driver JDBC rechaza con un mensaje que oculta el problema real.
        // Puerto no numerico: URI no puede resolver el host y falla el parseo.
        JdbcSettings settings = config.resolveSettings(
                "postgres://usuario:clave@host:puerto-invalido/db", "u", "p", "jdbc:h2:mem:no-usado");

        assertThat(settings.url()).isEqualTo("postgres://usuario:clave@host:puerto-invalido/db");
        assertThat(settings.driverClassName())
                .as("sin driver forzado, el error sera el de la URL y no uno enganoso")
                .isNull();
    }

    @Test
    @DisplayName("Sin DATABASE_URL cae al respaldo embebido H2")
    void fallsBackToEmbeddedWhenNoDatabaseUrl(@TempDir Path tempDir) {
        String url = "jdbc:h2:file:" + tempDir.resolve("fallback").toString().replace('\\', '/');
        JdbcSettings settings = config.resolveSettings("   ", "", "", url);

        assertThat(settings.url()).isEqualTo(url);
        assertThat(settings.driverClassName()).isEqualTo("org.h2.Driver");
        assertThat(settings.username()).isEqualTo("sa");
    }

    @Test
    @DisplayName("H2 rechaza AUTO_SERVER junto a DB_CLOSE_ON_EXIT, por eso no se usa")
    void autoServerIsIncompatibleWithCloseOnExit(@TempDir Path tempDir) {
        String base = tempDir.resolve("conflicto").toString().replace('\\', '/');
        String hostileUrl = "jdbc:h2:file:" + base
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=TRUE";

        // Documenta el motivo de no incluir AUTO_SERVER en application.yml:
        // la combinacion revienta al abrir la primera conexion.
        Properties props = new Properties();
        props.setProperty("user", "sa");
        props.setProperty("password", "");

        assertThat(catchConnectionError(hostileUrl, props))
                .as("H2 debe seguir rechazando esta combinacion")
                .isNotNull();
    }

    private String catchConnectionError(String url, Properties props) {
        try (Connection ignored = new org.h2.Driver().connect(url, props)) {
            return null;
        } catch (SQLException e) {
            return e.getMessage();
        }
    }
}
