package health.emrys.afon.security.keycloak;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A KeyCloak that has just started answers 5xx to a token request, eg 503 "Bootstrap in progress. Retry in 10 seconds.",
 * so that is retried, while failures that would recur, eg bad credentials, are not.
 */
class KeyCloakProviderTokenRetryTest {

    private final AtomicInteger requests = new AtomicInteger();
    /** The code answered to the requests before the one numbered {@link #succeedFrom}, which gets a token. */
    private volatile int failureCode = 503;
    private volatile int succeedFrom = 4; // after the first attempt, which the HTTP client itself makes twice
    private HttpServer keycloak;
    private KeyCloakProvider provider;

    @BeforeEach
    void startKeyCloak() throws IOException {
        keycloak = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        keycloak.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            boolean token = requests.incrementAndGet() >= succeedFrom;
            byte[] body = (token ? "{\"access_token\":\"the-token\"}" : "Bootstrap in progress. Retry in 10 seconds.")
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(token ? 200 : failureCode, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        keycloak.start();
        provider = new KeyCloakProvider("http://localhost:" + keycloak.getAddress().getPort(), "admin", "admin");
        provider.tokenRetryMillis = 1;
    }

    @AfterEach
    void stop() {
        keycloak.stop(0);
    }

    @Test
    void retriesAServerErrorUntilKeyCloakGivesAToken() throws IOException {
        assertThat(provider.adminToken()).isEqualTo("the-token");
        assertThat(requests.get()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void retriesABadGatewayFromTheIngressToo() throws IOException {
        failureCode = 502;

        assertThat(provider.adminToken()).isEqualTo("the-token");
    }

    @Test
    void givesUpAfterTheAttemptsWithTheLastError() {
        succeedFrom = Integer.MAX_VALUE;
        provider.tokenAttempts = 3;

        assertThatThrownBy(() -> provider.adminToken()).isInstanceOf(IOException.class)
            .hasMessageContaining("code: 503").hasMessageContaining("Bootstrap in progress");
        // at least one request for each attempt, the HTTP client itself retrying a 503 once more
        assertThat(requests.get()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void doesNotRetryAFailureThatWouldRecur() {
        failureCode = 401;
        succeedFrom = Integer.MAX_VALUE;

        assertThatThrownBy(() -> provider.adminToken()).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("code: 401");
        assertThat(requests).hasValue(1);
    }
}
