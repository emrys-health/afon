package health.emrys.afon.ops.steps;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepActionsCheckAnswersTest {

    private HttpServer server;
    private final java.util.concurrent.atomic.AtomicInteger starting = new java.util.concurrent.atomic.AtomicInteger();
    private final StepActions actions = new StepActions();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> reply(exchange, 200));
        server.createContext("/needs-auth", exchange -> reply(exchange, 401));
        server.createContext("/broken", exchange -> reply(exchange, 503));
        server.createContext("/starting", exchange -> reply(exchange, starting.incrementAndGet() > 2 ? 200 : 503));
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int code) throws java.io.IOException {
        exchange.sendResponseHeaders(code, -1);
        exchange.close();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Test
    void answersWith200ByDefault() {
        assertThatCode(() -> actions.checkAnswers(url("/ok"), "deploy it")).doesNotThrowAnyException();
        assertThatThrownBy(() -> actions.checkAnswers(url("/needs-auth"), "deploy it"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("answered 401").hasMessageContaining("deploy it");
    }

    @Test
    void answersWithAnyOfTheGivenCodes() {
        Set<Integer> codes = Set.of(200, 401, 403);
        assertThatCode(() -> actions.checkAnswers(url("/ok"), "deploy it", codes)).doesNotThrowAnyException();
        assertThatCode(() -> actions.checkAnswers(url("/needs-auth"), "deploy it", codes)).doesNotThrowAnyException();
        assertThatThrownBy(() -> actions.checkAnswers(url("/broken"), "deploy it", codes))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("answered 503");
    }

    @Test
    void failsIfNothingListens() {
        server.stop(0);
        assertThatThrownBy(() -> actions.checkAnswers(url("/ok"), "deploy it", Set.of(200, 401)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot be reached").hasMessageContaining("deploy it");
    }

    @Test
    void waitsUntilItAnswers() throws Throwable {
        actions.waitOnAnswer(url("/starting"), 30_000, 10).getFunction().run();

        org.assertj.core.api.Assertions.assertThat(starting.get()).isEqualTo(3);
    }

    @Test
    void givesUpAtTheTimeoutWithoutFailing() throws Throwable {
        long start = System.currentTimeMillis();

        actions.waitOnAnswer(url("/broken"), 300, 50).getFunction().run();

        org.assertj.core.api.Assertions.assertThat(System.currentTimeMillis() - start).isBetween(250L, 5_000L);
    }
}
