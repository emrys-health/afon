package health.emrys.afon.security.keycloak;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import health.emrys.afon.config.AfonUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KeyCloak admin requests carry secrets in their JSON (a user's password, a client secret), which must
 * never be logged, whatever the outcome.
 */
class KeyCloakProviderLoggingTest {

    private static final String PASSWORD = "s3cret-password-never-logged";

    private final AtomicInteger postCode = new AtomicInteger(201);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger afonLogger;
    private Level afonLevel;
    private HttpServer keycloak;
    private KeyCloakProvider provider;

    @BeforeEach
    void startKeyCloakAndCaptureLogs() throws IOException {
        keycloak = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        keycloak.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int code = exchange.getRequestMethod().equals("POST") ? postCode.get() : 204;
            byte[] body = (code / 100 == 2 ? "" : "{\"error\":\"from keycloak\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        keycloak.start();
        provider = new KeyCloakProvider("http://localhost:" + keycloak.getAddress().getPort(), "admin", "admin");

        afonLogger = (Logger) LoggerFactory.getLogger("health.emrys.afon");
        afonLevel = afonLogger.getLevel();
        afonLogger.setLevel(Level.TRACE);
        logs.start();
        afonLogger.addAppender(logs);
    }

    @AfterEach
    void stop() {
        afonLogger.detachAppender(logs);
        afonLogger.setLevel(afonLevel);
        keycloak.stop(0);
    }

    @Test
    void doesNotLogTheUsersPasswordWhenCreatingAUser() throws IOException {
        provider.createUser("a_token", "polaris", user());

        assertLogsMention("user will");
        assertLogsDoNotContain(PASSWORD);
    }

    @Test
    void doesNotLogTheUsersPasswordWhenTheRequestFails() {
        postCode.set(500);

        assertThatThrownBy(() -> provider.createUser("a_token", "polaris", user())).isInstanceOf(IOException.class);

        assertLogsMention("from keycloak");
        assertLogsDoNotContain(PASSWORD);
    }

    @Test
    void doesNotLogTheJsonOfAnUpdate() throws IOException {
        postCode.set(409); // the realm exists, so it is updated with a PUT

        provider.createRealm("a_token", "polaris");

        assertLogsMention("Calling PUT");
        assertLogsDoNotContain("accessTokenLifespan");
    }

    private static AfonUser user() {
        return new AfonUser("will", PASSWORD, "will@test.com", "slug", "Will", "Test");
    }

    private void assertLogsMention(String text) {
        assertThat(messages()).anyMatch(message -> message.contains(text));
    }

    private void assertLogsDoNotContain(String text) {
        assertThat(messages()).noneMatch(message -> message.contains(text));
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
