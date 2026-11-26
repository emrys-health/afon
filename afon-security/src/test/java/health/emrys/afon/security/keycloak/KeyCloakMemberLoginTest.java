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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The login of a member to a project's client, against a server that answers as KeyCloak's pages and token endpoint do: the form of the password, the form of the
 * one-time password, a redirect to the client's redirect URI with a code, and a token for a code that is presented with the client's secret and the PKCE verifier.
 */
class KeyCloakMemberLoginTest {

    private static final String CLIENT = "xyz-yyyy-nnnn-tre";
    private static final String SECRET = "the-secret-of-the-client";
    private static final String REDIRECT = "https://jupyter.example.org/hub/oauth_callback";
    private static final String TOTP_SECRET = "the-secret-of-the-authenticator";
    /** The secret that the page of the enrolment shows. */
    private static final String ENROLLED = "the-secret-the-realm-shows";

    private HttpServer server;
    private URI realm;
    /** What the server saw of the login. */
    private final Map<String, String> authRequest = new LinkedHashMap<>();
    private boolean enrolling;
    private volatile boolean firstCodeUsedByAnother;
    private boolean redirectsWithAnError;
    private boolean sawTheCookie;

    @BeforeEach
    void keycloak() throws IOException {
        firstCodeUsedByAnother = false;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        realm = URI.create(base + "/realms/polaris");
        server.createContext("/realms/polaris/protocol/openid-connect/auth", exchange -> {
            authRequest.putAll(form(exchange.getRequestURI().getRawQuery()));
            exchange.getResponseHeaders().add("Set-Cookie", "AUTH_SESSION_ID=session-1; Path=/; Secure; HttpOnly");
            page(exchange, "<form action=\"" + base + "/login?session_code=1&amp;execution=2\" method=\"post\"><input name=\"username\"><div id=\"input-error-container-username\" class=\"pf-m-error\"></div><input name=\"password\"></form>");
        });
        server.createContext("/login", exchange -> {
            sawTheCookie = String.valueOf(exchange.getRequestHeaders().getFirst("Cookie")).contains("AUTH_SESSION_ID=session-1");
            Map<String, String> fields = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (!"ada".equals(fields.get("username")) || !"right-password".equals(fields.get("password"))) {
                page(exchange, "<span id=\"kc-error-message\">Invalid username or password.</span><form action=\"" + base + "/login\"><input name=\"username\"></form>");
            } else if (enrolling) {
                page(exchange, "<form action=\"" + base + "/enrol\" method=\"post\"><input name=\"totpSecret\" type=\"hidden\" value=\"" + ENROLLED + "\"></form>");
            } else {
                page(exchange, "<form action=\"/otp\" method=\"post\"><input name=\"otp\"></form>");
            }
        });
        server.createContext("/otp", exchange -> {
            Map<String, String> fields = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            long now = System.currentTimeMillis() / 1000 / 30;
            boolean right = false;
            for (long step = now - 1; step <= now + 1; step++) {
                right |= OneTimePassword.code(TOTP_SECRET, step).equals(fields.get("otp"));
            }
            if (firstCodeUsedByAnother) {
                // as the realm refuses a code that has been used, by this member, from another process
                firstCodeUsedByAnother = false;
                right = false;
            }
            if (redirectsWithAnError) {
                exchange.getResponseHeaders().add("Location", REDIRECT + "?error=access_denied&error_description=not+a+member&state=" + authRequest.get("state"));
            } else if (right) {
                exchange.getResponseHeaders().add("Location", REDIRECT + "?code=the-code&state=" + authRequest.get("state"));
            } else {
                page(exchange, "<span id=\"kc-error-message\">Invalid authenticator code.</span><form action=\"/otp\"><input name=\"otp\"></form>");
                return;
            }
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/enrol", exchange -> {
            Map<String, String> fields = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            long now = System.currentTimeMillis() / 1000 / 30;
            boolean right = ENROLLED.equals(fields.get("totpSecret")) && fields.get("userLabel") != null && !fields.get("userLabel").isBlank();
            boolean codeIsRight = false;
            for (long step = now - 1; step <= now + 1; step++) {
                codeIsRight |= OneTimePassword.code(ENROLLED, step).equals(fields.get("totp"));
            }
            if (right && codeIsRight) {
                exchange.getResponseHeaders().add("Location", REDIRECT + "?code=the-code&state=" + authRequest.get("state"));
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            } else {
                page(exchange, "<span id=\"kc-error-message\">Invalid authenticator code.</span>");
            }
        });
        server.createContext("/realms/polaris/protocol/openid-connect/token", exchange -> {
            Map<String, String> fields = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(fields.getOrDefault("code_verifier", "")));
            boolean fine = "the-code".equals(fields.get("code")) && SECRET.equals(fields.get("client_secret")) && CLIENT.equals(fields.get("client_id"))
                           && REDIRECT.equals(fields.get("redirect_uri")) && challenge.equals(authRequest.get("code_challenge"));
            byte[] body = (fine ? "{\"access_token\":\"" + jwt("{\"project\":[\"xyz-yyyy-nnnn\"],\"acr\":\"mfa\",\"aud\":[\"provider-lon\"]}") + "\"}"
                                : "{\"error\":\"invalid_grant\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(fine ? 200 : 401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void page(HttpExchange exchange, String html) throws IOException {
        byte[] body = ("<html><body>" + html + "</body></html>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static Map<String, String> form(String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : text == null || text.isEmpty() ? new String[0] : text.split("&")) {
            String[] kv = pair.split("=", 2);
            fields.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return fields;
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String jwt(String claims) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "." + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    private KeyCloakMemberLogin.Token login(String password, String totpSecret) throws Exception {
        return new KeyCloakMemberLogin(realm).login(CLIENT, SECRET, REDIRECT, "ada", password, totpSecret);
    }

    @Test
    void givesTheTokenOfTheClientWithItsClaimsAfterThePasswordAndTheOneTimePassword() throws Exception {
        KeyCloakMemberLogin.Token token = login("right-password", TOTP_SECRET);

        assertThat(token.claims().path("project").get(0).asText()).isEqualTo("xyz-yyyy-nnnn");
        assertThat(token.claims().path("acr").asText()).isEqualTo("mfa");
        assertThat(token.accessToken()).startsWith("eyJ");
        assertThat(token.totpSecret()).isEqualTo(TOTP_SECRET);
        // as a browser does: the session cookie that the first page set comes back, and the login asks the realm for a code with a challenge
        assertThat(sawTheCookie).isTrue();
        assertThat(authRequest).containsEntry("client_id", CLIENT).containsEntry("response_type", "code").containsEntry("code_challenge_method", "S256");
    }

    @Test
    void saysSoWhenThePasswordIsWrong() {
        assertThatThrownBy(() -> login("wrong-password", TOTP_SECRET)).isInstanceOf(KeyCloakMemberLogin.MemberLoginException.class)
            .hasMessageContaining("Invalid username or password");
    }

    @Test
    void saysSoWhenTheOneTimePasswordIsWrong() {
        assertThatThrownBy(() -> login("right-password", "another-authenticators-secret")).isInstanceOf(KeyCloakMemberLogin.MemberLoginException.class)
            .hasMessageContaining("Invalid authenticator code");
    }

    @Test
    void enrolsAnAuthenticatorForAMemberWhoHasNoneWhenGivenNoSecretAndGivesItsSecretBack() throws Exception {
        enrolling = true;

        KeyCloakMemberLogin.Token token = login("right-password", null);

        assertThat(token.totpSecret()).isEqualTo(ENROLLED);
        assertThat(token.claims().path("acr").asText()).isEqualTo("mfa");
    }

    @Test
    void doesNotEnrolWhenAsecretWasGivenAsItIsThenNotTheMembers() {
        enrolling = true;

        assertThatThrownBy(() -> login("right-password", TOTP_SECRET)).isInstanceOf(KeyCloakMemberLogin.MemberLoginException.class)
            .hasMessageContaining("enrol an authenticator").hasMessageContaining("a secret was given").hasMessageContaining("ada");
    }

    @Test
    void saysSoWhenTheRealmAsksForACodeAndNoSecretWasGiven() {
        assertThatThrownBy(() -> login("right-password", null)).isInstanceOf(KeyCloakMemberLogin.MemberLoginException.class)
            .hasMessageContaining("one-time password").hasMessageContaining("no authenticator secret was given");
    }

    @Test
    void saysWhyTheRealmRefusedAMemberItRedirectsWithAnError() {
        redirectsWithAnError = true;

        assertThatThrownBy(() -> login("right-password", TOTP_SECRET)).isInstanceOf(KeyCloakMemberLogin.MemberLoginException.class)
            .hasMessageContaining("refused the login").hasMessageContaining("access_denied");
    }

    @Test
    void saysWhatTheTokenEndpointAnswersWhenItRefusesTheCode() {
        assertThatThrownBy(() -> new KeyCloakMemberLogin(realm).login(CLIENT, "not-the-secret", REDIRECT, "ada", "right-password", TOTP_SECRET))
            .isInstanceOf(KeyCloakMemberLogin.MemberLoginException.class).hasMessageContaining("HTTP 401").hasMessageContaining("invalid_grant");
    }

    @Test
    void tellsTheSecretAsSoonAsTheRealmHasTheAuthenticatorSoThatItIsNotLostWhenTheLoginFailsAfterIt() {
        enrolling = true;
        java.util.List<String> told = new java.util.ArrayList<>();

        assertThatThrownBy(() -> new KeyCloakMemberLogin(realm).login(CLIENT, "not-the-secret", REDIRECT, "ada", "right-password", null, told::add))
            .isInstanceOf(KeyCloakMemberLogin.MemberLoginException.class).hasMessageContaining("HTTP 401");

        assertThat(told).containsExactly(ENROLLED);
    }

    @Test
    void sendsTheCodeOnceMoreWithAnotherTimeStepWhenTheRealmRefusesTheFirstAsUsed() throws Exception {
        firstCodeUsedByAnother = true;

        KeyCloakMemberLogin.Token token = login("right-password", TOTP_SECRET);

        assertThat(token.accessToken()).startsWith("eyJ");
    }
}
