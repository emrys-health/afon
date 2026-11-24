package health.emrys.afon.security.keycloak;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SMTP server that the realm sends its emails through, from the properties of the env of the KeyCloak.
 */
class KeyCloakSmtpTest {

    @TempDir
    Path dir;

    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private HttpServer keycloak;

    @BeforeEach
    void startKeyCloak() throws IOException {
        keycloak = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        keycloak.createContext("/", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String path = exchange.getRequestURI().getPath();
            reply(exchange, path.endsWith("/protocol/openid-connect/token") ? 200 : 201,
                  path.endsWith("/protocol/openid-connect/token") ? "{\"access_token\":\"the-token\"}" : "");
        });
        keycloak.start();
    }

    @AfterEach
    void stop() {
        keycloak.stop(0);
    }

    private static void reply(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private AfonPropertyProvider properties(String afon, String secret) throws IOException {
        Files.writeString(dir.resolve("afon.properties"), afon);
        Files.writeString(dir.resolve("secret.properties"), secret);
        return new FilePropertyProvider(dir.toString(), List.of("afon", "secret"));
    }

    @Test
    void readsTheServerFromTheProperties() throws IOException {
        KeyCloakProvider.Smtp smtp = KeyCloakProvider.Smtp.of(properties(
            "KEYCLOAK_SMTP_HOST=smtp.example.org\nKEYCLOAK_SMTP_PORT=2525\nKEYCLOAK_SMTP_FROM=tre@example.org\nKEYCLOAK_SMTP_SECURITY=SSL\n",
            "KEYCLOAK_SMTP_USERNAME=mailer\nKEYCLOAK_SMTP_PASSWORD=s3cret\n"));

        assertThat(smtp.host()).isEqualTo("smtp.example.org");
        assertThat(smtp.port()).isEqualTo(2525);
        assertThat(smtp.from()).isEqualTo("tre@example.org");
        assertThat(smtp.security()).isEqualTo(KeyCloakProvider.Smtp.Security.SSL);
        assertThat(smtp.user()).isEqualTo("mailer");
        assertThat(smtp.realmSettings()).containsEntry("ssl", "true").containsEntry("starttls", "false").containsEntry("auth", "true")
                                        .containsEntry("user", "mailer").containsEntry("password", "s3cret");
    }

    @Test
    void usesTheSubmissionPortWithStarttlsAndNoAuthenticationUnlessTold() throws IOException {
        KeyCloakProvider.Smtp smtp = KeyCloakProvider.Smtp.of(properties(
            "KEYCLOAK_SMTP_HOST=smtp.example.org\nKEYCLOAK_SMTP_FROM=tre@example.org\n", "KEYCLOAK_SMTP_USERNAME=\nKEYCLOAK_SMTP_PASSWORD=\n"));

        assertThat(smtp.port()).isEqualTo(587);
        assertThat(smtp.security()).isEqualTo(KeyCloakProvider.Smtp.Security.STARTTLS);
        assertThat(smtp.realmSettings()).containsEntry("starttls", "true").containsEntry("ssl", "false").containsEntry("auth", "false")
                                        .doesNotContainKeys("user", "password");
    }

    @Test
    void hasNoServerWhenTheHostIsNotSet() throws IOException {
        assertThat(KeyCloakProvider.Smtp.of(properties("KEYCLOAK_SMTP_HOST=\nKEYCLOAK_SMTP_FROM=\n", ""))).isNull();
        assertThat(KeyCloakProvider.Smtp.of(properties("ENV_NAME=dev\n", ""))).isNull();
    }

    @Test
    void refusesAHostWithNoAddressToSendFromAndAnInvalidPortOrSecurity() {
        assertThatThrownBy(() -> KeyCloakProvider.Smtp.of(properties("KEYCLOAK_SMTP_HOST=smtp.example.org\n", "")))
            .hasMessageContaining("KEYCLOAK_SMTP_FROM");
        assertThatThrownBy(() -> KeyCloakProvider.Smtp.of(properties(
            "KEYCLOAK_SMTP_HOST=smtp.example.org\nKEYCLOAK_SMTP_FROM=a@b.c\nKEYCLOAK_SMTP_PORT=x\n", "")))
            .hasMessageContaining("KEYCLOAK_SMTP_PORT");
        assertThatThrownBy(() -> KeyCloakProvider.Smtp.of(properties(
            "KEYCLOAK_SMTP_HOST=smtp.example.org\nKEYCLOAK_SMTP_FROM=a@b.c\nKEYCLOAK_SMTP_SECURITY=tls\n", "")))
            .hasMessageContaining("KEYCLOAK_SMTP_SECURITY");
    }

    @Test
    void neverSaysThePasswordWhenItIsPrinted() {
        assertThat(new KeyCloakProvider.Smtp("smtp.example.org", 587, "a@b.c", KeyCloakProvider.Smtp.Security.STARTTLS, "mailer", "s3cret").toString())
            .contains("smtp.example.org").doesNotContain("s3cret");
    }

    @Test
    void putsTheServerInTheRealmWhenItIsMadeAndOtherwiseLeavesTheRealmAsItIs() throws IOException {
        KeyCloakProvider provider = new KeyCloakProvider("http://localhost:" + keycloak.getAddress().getPort(), "admin", "admin");
        provider.createRealm("the-token", "polaris");
        assertThat(bodies.get(bodies.size() - 1)).doesNotContain("smtpServer");

        provider.withSmtp(new KeyCloakProvider.Smtp("smtp.example.org", 587, "tre@example.org", KeyCloakProvider.Smtp.Security.STARTTLS, null, null))
                .createRealm("the-token", "polaris");
        assertThat(bodies.get(bodies.size() - 1)).contains("\"smtpServer\":{", "\"host\":\"smtp.example.org\"", "\"from\":\"tre@example.org\"",
                                                           "\"starttls\":\"true\"");
    }
}
