package health.emrys.afon.security.keycloak;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.AfonUserProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The KeyCloak database outlives the stacks, so a user that setup creates may already exist, with the password it was given
 * by an earlier setup: users.csv is the source of truth, so the password is set again.
 */
class KeyCloakProviderExistingUserTest {

    private static final String PASSWORD = "n3w-passw0rd";

    /** The requests received, eg {@code PUT /admin/realms/polaris/users/the-id/reset-password}. */
    private final List<String> requests = new ArrayList<>();
    private final List<String> bodies   = new ArrayList<>();
    private volatile boolean   userExists = true;
    private volatile int       resetCode  = 204;
    private HttpServer         keycloak;
    private KeyCloakProvider   provider;

    @BeforeEach
    void startKeyCloak() throws IOException {
        keycloak = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        keycloak.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String call = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            requests.add(call);
            bodies.add(body);
            if (call.equals("POST /admin/realms/polaris/users")) {
                reply(exchange, 409, "{}");
            } else if (call.equals("GET /admin/realms/polaris/users")) {
                reply(exchange, 200, userExists ? "[{\"id\":\"the-id\",\"username\":\"mauro\"}]" : "[]");
            } else if (call.equals("PUT /admin/realms/polaris/users/the-id/reset-password")) {
                reply(exchange, resetCode, "");
            } else {
                reply(exchange, 404, "");
            }
        });
        keycloak.start();
        provider = new KeyCloakProvider("http://localhost:" + keycloak.getAddress().getPort(), "admin", "admin");
    }

    private static void reply(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @AfterEach
    void stop() {
        keycloak.stop(0);
    }

    private static AfonUser user() {
        return AfonUserProvider.createUser("mauro", PASSWORD);
    }

    @Test
    void setsThePasswordOfAnExistingUser() throws IOException {
        provider.createUser("token", "polaris", user());

        assertThat(requests).containsSubsequence("POST /admin/realms/polaris/users",
                                                 "GET /admin/realms/polaris/users",
                                                 "PUT /admin/realms/polaris/users/the-id/reset-password");
        String reset = bodies.get(requests.indexOf("PUT /admin/realms/polaris/users/the-id/reset-password"));
        assertThat(reset).contains("\"type\":\"password\"").contains("\"value\":\"" + PASSWORD + "\"")
                         .contains("\"temporary\":false");
    }

    @Test
    void failsWithoutRevealingThePasswordIfItCannotBeSet() {
        resetCode = 400;
        assertThatThrownBy(() -> provider.createUser("token", "polaris", user()))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("'mauro'")
            .hasMessageContaining("HTTP 400")
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain(PASSWORD));
    }

    @Test
    void stillFailsWhenTheConflictIsNotTheUsername() {
        userExists = false;
        assertThatThrownBy(() -> provider.createUser("token", "polaris", user()))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("no user with that username exists");
        assertThat(requests).doesNotContain("PUT /admin/realms/polaris/users/the-id/reset-password");
    }
}
