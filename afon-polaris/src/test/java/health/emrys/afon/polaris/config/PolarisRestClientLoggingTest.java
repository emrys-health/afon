package health.emrys.afon.polaris.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
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

/**
 * Polaris management requests can carry secrets in their JSON, eg the client secret an external catalog
 * uses to reach the remote Polaris, which must never be logged, whatever the outcome.
 */
class PolarisRestClientLoggingTest {

    private static final String SECRET = "s3cret-never-logged";
    private static final String CATALOG_JSON = format(
        "{\"catalog\":{\"name\":\"external\",\"connectionConfigInfo\":{\"authenticationParameters\":"
            + "{\"clientId\":\"root\",\"clientSecret\":\"%s\"}}}}");

    private final AtomicInteger code = new AtomicInteger(201);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger afonLogger;
    private Level afonLevel;
    private HttpServer polaris;
    private PolarisRestClient client;

    private static String format(String template) {
        return String.format(template, SECRET);
    }

    @BeforeEach
    void startPolarisAndCaptureLogs() throws IOException {
        polaris = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        polaris.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = (code.get() / 100 == 2 ? "{}" : "{\"error\":\"from polaris\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        polaris.start();
        client = new PolarisRestClient("http://localhost:" + polaris.getAddress().getPort(), "root", "admin_secret");

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
        polaris.stop(0);
    }

    @Test
    void doesNotLogTheSecretsInAPost() throws IOException {
        client.post("/api/management/v1/catalogs", CATALOG_JSON, "a_token");

        assertLogsMention("POST /api/management/v1/catalogs");
        assertLogsDoNotContain(SECRET);
    }

    @Test
    void doesNotLogTheSecretsInAPut() throws IOException {
        client.put("/api/management/v1/catalogs/external", CATALOG_JSON, "a_token");

        assertLogsMention("PUT /api/management/v1/catalogs/external");
        assertLogsDoNotContain(SECRET);
    }

    @Test
    void doesNotLogTheSecretsWhenTheRequestFails() throws IOException {
        code.set(400);

        client.post("/api/management/v1/catalogs", CATALOG_JSON, "a_token");

        assertLogsMention("from polaris");
        assertLogsDoNotContain(SECRET);
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
