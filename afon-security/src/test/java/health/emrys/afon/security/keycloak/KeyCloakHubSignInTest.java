package health.emrys.afon.security.keycloak;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sign-in of a member to a TRE's JupyterHub, against a server that answers as the gate in front of the hub, the realm and the hub do: the realm is at 127.0.0.1 and the
 * TRE at localhost, the same server, so that each has the cookies that it set and not those of the other.
 */
class KeyCloakHubSignInTest {

    private static final String SECRET = "JBSWY3DPEHPK3PXP";

    private HttpServer server;
    private String realmBase;
    private String treBase;
    private final List<String> requests = new ArrayList<>();

    @BeforeEach
    void servers() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        realmBase = "http://127.0.0.1:" + port;
        treBase = "http://localhost:" + port;
        // the gate: the page with its button, until the realm has signed the member in to it
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            if (cookie(exchange, "gate") == null) {
                page(exchange, 403, "<form method=\"GET\" action=\"/oauth2/start\"><input type=\"hidden\" name=\"rd\" value=\"/\"><button>Sign in with OpenID Connect</button></form>");
            } else if (cookie(exchange, "hub") == null) {
                page(exchange, 200, "<a role=\"button\" class='btn btn-jupyter btn-lg' href='/hub/oauth_login?next=%2F'>Sign in with OAuth 2.0</a>");
            } else {
                redirect(exchange, "/hub/spawn-pending/ada");
            }
        });
        server.createContext("/oauth2/start", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            redirect(exchange, realmBase + "/realms/polaris/auth?ru=" + encode(treBase + "/oauth2/callback"));
        });
        server.createContext("/oauth2/callback", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            exchange.getResponseHeaders().add("Set-Cookie", "gate=1; Path=/; Secure; HttpOnly");
            redirect(exchange, "/");
        });
        server.createContext("/hub/oauth_login", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            redirect(exchange, realmBase + "/realms/polaris/auth?ru=" + encode(treBase + "/hub/oauth_callback"));
        });
        server.createContext("/hub/oauth_callback", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            exchange.getResponseHeaders().add("Set-Cookie", "hub=1; Path=/");
            redirect(exchange, "/hub/spawn-pending/ada");
        });
        server.createContext("/hub/spawn-pending/ada", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            page(exchange, 200, "<h1>Your server is starting up</h1>");
        });
        // the realm: the password, then the code, then the member has a session that signs them in to the next client with no page
        server.createContext("/realms/polaris/auth", exchange -> {
            requests.add("realm " + exchange.getRequestURI());
            String back = param(exchange.getRequestURI().getRawQuery(), "ru");
            if (cookie(exchange, "SSO") != null) {
                redirect(exchange, back + "?code=c");
            } else {
                page(exchange, 200, "<form action=\"" + realmBase + "/realms/login?ru=" + encode(back) + "\" method=\"post\"><input name=\"username\"><input name=\"password\"></form>");
            }
        });
        server.createContext("/realms/login", exchange -> {
            String back = param(exchange.getRequestURI().getRawQuery(), "ru");
            Map<String, String> fields = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (!"right".equals(fields.get("password"))) {
                page(exchange, 200, "<span id=\"kc-error-message\">Invalid username or password.</span><form action=\"" + realmBase + "/realms/login\"><input name=\"username\"></form>");
            } else {
                page(exchange, 200, "<form action=\"" + realmBase + "/realms/otp?ru=" + encode(back) + "\" method=\"post\"><input name=\"otp\"></form>");
            }
        });
        server.createContext("/realms/otp", exchange -> {
            String back = param(exchange.getRequestURI().getRawQuery(), "ru");
            Map<String, String> fields = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            long now = System.currentTimeMillis() / 1000 / 30;
            boolean right = false;
            for (long step = now - 1; step <= now + 1; step++) {
                right |= OneTimePassword.code(SECRET, step).equals(fields.get("otp"));
            }
            if (right) {
                exchange.getResponseHeaders().add("Set-Cookie", "SSO=1; Path=/");
                redirect(exchange, back + "?code=c");
            } else {
                page(exchange, 200, "<span id=\"kc-error-message\">Invalid authenticator code.</span><form action=\"" + realmBase + "/realms/otp\"><input name=\"otp\"></form>");
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private KeyCloakMemberLogin login() {
        return new KeyCloakMemberLogin(URI.create(realmBase + "/realms/polaris"));
    }

    @Test
    void signsTheMemberInThroughTheGateAndTheHubAndEndsAtThePageOfTheServerThatIsStarting() throws Exception {
        KeyCloakMemberLogin.HubLanding landing = login().signInToHub(URI.create(treBase + "/"), "ada", "right", SECRET);

        assertThat(landing.at().getPath()).isEqualTo("/hub/spawn-pending/ada");
        assertThat(landing.status()).isEqualTo(200);
        assertThat(landing.text()).contains("Your server is starting up");
        // the gate's button was pressed with its hidden field, the hub's link was followed, and the realm asked once, as the member had its session for the hub
        assertThat(requests).contains("/oauth2/start?rd=%2F", "/hub/oauth_login?next=%2F");
        assertThat(requests.stream().filter(r -> r.startsWith("realm ")).count()).isEqualTo(2);
    }

    @Test
    void saysSoWhenTheRealmRefusesThePassword() {
        assertThatThrownBy(() -> login().signInToHub(URI.create(treBase + "/"), "ada", "wrong", SECRET))
            .isInstanceOf(KeyCloakMemberLogin.MemberLoginException.class).hasMessageContaining("Invalid username or password");
    }

    private static String cookie(HttpExchange exchange, String name) {
        String header = exchange.getRequestHeaders().getFirst("Cookie");
        if (header == null) {
            return null;
        }
        for (String pair : header.split(";\\s*")) {
            if (pair.startsWith(name + "=")) {
                return pair.substring(name.length() + 1);
            }
        }
        return null;
    }

    private static void redirect(HttpExchange exchange, String to) throws IOException {
        exchange.getResponseHeaders().add("Location", to);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void page(HttpExchange exchange, int status, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private static String param(String query, String name) {
        for (String pair : query.split("&")) {
            if (pair.startsWith(name + "=")) {
                return URLDecoder.decode(pair.substring(name.length() + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static Map<String, String> form(String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : text.split("&")) {
            String[] kv = pair.split("=", 2);
            fields.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return fields;
    }
}
