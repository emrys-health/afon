package health.emrys.afon.rest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestClientTest {

    private static final String SECRET = "s3cret-never-logged";

    private final AtomicInteger code = new AtomicInteger(200);
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> uri = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> customHeader = new AtomicReference<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger afonLogger;
    private Level afonLevel;
    private HttpServer server;
    private RestClient client;

    @BeforeEach
    void startServerAndCaptureLogs() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            uri.set(exchange.getRequestURI().toString());
            authorization.set(exchange.getRequestHeaders().getFirst(RestClient.AUTHORIZATION));
            contentType.set(exchange.getRequestHeaders().getFirst(RestClient.CONTENT_TYPE));
            customHeader.set(exchange.getRequestHeaders().getFirst("X-Custom-Header"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = (code.get() / 100 == 2 ? "{\"name\":\"a_name\"}" : "{\"error\":\"from server\"}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new RestClient("http://localhost:" + server.getAddress().getPort());

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
        server.stop(0);
    }

    @Test
    void getsWithTheAccessTokenAndReturnsAReadableResponse() throws Exception {
        ClassicHttpResponse response = client.get("/things/a_name", "a_token");

        assertThat(method.get()).isEqualTo("GET");
        assertThat(uri.get()).isEqualTo("/things/a_name");
        assertThat(authorization.get()).isEqualTo("Bearer a_token");
        assertThat(response.getCode()).isEqualTo(200);
        assertThat(EntityUtils.toString(response.getEntity())).isEqualTo("{\"name\":\"a_name\"}");
        assertLogsMention("GET /things/a_name");
    }

    @Test
    void deletes() throws Exception {
        ClassicHttpResponse response = client.delete("/things/a_name", "a_token");

        assertThat(method.get()).isEqualTo("DELETE");
        assertThat(response.getCode()).isEqualTo(200);
    }

    @Test
    void sendsJsonAsUtf8() throws Exception {
        client.put("/things/a_name", "{\"name\":\"Zoë\"}", "a_token");

        assertThat(method.get()).isEqualTo("PUT");
        assertThat(contentType.get()).startsWith("application/json").containsIgnoringCase("UTF-8");
        assertThat(requestBody.get()).isEqualTo("{\"name\":\"Zoë\"}");
    }

    @Test
    void postsAnEncodedFormWithoutAnAccessToken() throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        form.put("client_secret", "a&b=c");

        String body = client.postForm("/token", form, response -> EntityUtils.toString(response.getEntity()),
                                      new BasicHeader("X-Custom-Header", "a_value"));

        assertThat(method.get()).isEqualTo("POST");
        assertThat(authorization.get()).isNull();
        assertThat(customHeader.get()).isEqualTo("a_value");
        assertThat(contentType.get()).startsWith("application/x-www-form-urlencoded");
        assertThat(requestBody.get()).isEqualTo("grant_type=client_credentials&client_secret=a%26b%3Dc");
        assertThat(body).isEqualTo("{\"name\":\"a_name\"}");
    }

    @Test
    void sendsOnlyTheRequestedHeaders() throws Exception {
        client.get("/things", "a_token");

        assertThat(customHeader.get()).isNull();
    }

    @Test
    void doesNotLogTheBody() throws Exception {
        client.post("/things", "{\"secret\":\"" + SECRET + "\"}", "a_token");

        assertLogsMention("POST /things");
        assertLogsDoNotContain(SECRET);
    }

    @Test
    void logsTheResponseButNotTheBodyWhenTheRequestFails() throws Exception {
        code.set(400);

        ClassicHttpResponse response = client.post("/things", "{\"secret\":\"" + SECRET + "\"}", "a_token");

        assertThat(response.getCode()).isEqualTo(400);
        assertThat(EntityUtils.toString(response.getEntity())).contains("from server");
        assertLogsMention("from server");
        assertLogsDoNotContain(SECRET);
    }

    @Test
    void existsIsTrueOnA200() throws Exception {
        assertThat(client.exists("/things/a_name", "a_token")).isTrue();
        assertThat(method.get()).isEqualTo("GET");
        assertThat(authorization.get()).isEqualTo("Bearer a_token");
    }

    @Test
    void existsIsFalseOnA404WithoutLoggingAWarning() throws Exception {
        code.set(404);

        assertThat(client.exists("/things/a_name", "a_token")).isFalse();
        assertThat(logs.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
    }

    @Test
    void existsRaisesAnyOtherCodeRatherThanReadingItAsAbsent() {
        for (int failure : new int[]{403, 500}) {
            code.set(failure);
            assertThatThrownBy(() -> client.exists("/things/a_name", "a_token"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(String.valueOf(failure));
        }
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
