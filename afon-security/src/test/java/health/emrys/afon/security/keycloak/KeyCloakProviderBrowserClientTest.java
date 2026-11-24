package health.emrys.afon.security.keycloak;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import health.emrys.afon.security.IdentityProvider.RedirectStrategy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The browser client that logins to Jupyter go through is made, or, if it is already there, overwritten, as setup registers it, and a setup that could not do either
 * fails, instead of leaving the client as an earlier deploy, or another cloud, left it, which only shows when somebody tries to log in.
 */
class KeyCloakProviderBrowserClientTest {

    private static final List<String> URIS = KeyCloakProvider.callbackURIs("azure-jupyter.example.org");

    private final List<String> requests = new ArrayList<>();
    private volatile int     createCode = 409;
    private volatile int     updateCode = 204;
    private volatile boolean found      = true;
    private volatile String  putBody;
    private HttpServer       keycloak;
    private KeyCloakProvider provider;

    @BeforeEach
    void startKeyCloak() throws IOException {
        keycloak = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        keycloak.createContext("/", exchange -> {
            String call = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            requests.add(call);
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (exchange.getRequestURI().getPath().endsWith("/protocol/openid-connect/token")) {
                reply(exchange, 200, "{\"access_token\":\"token\",\"expires_in\":300}");
            } else if (call.equals("POST /admin/realms/polaris/clients")) {
                reply(exchange, createCode, "{\"errorMessage\":\"nope\"}");
            } else if (call.equals("GET /admin/realms/polaris/clients")) {
                reply(exchange, 200, found ? "[{\"id\":\"the-id\"}]" : "[]");
            } else if (call.equals("GET /admin/realms/polaris/clients/the-id")) {
                // the redirect URIs of another cloud's Jupyter, which the overwrite drops
                reply(exchange, 200, "{\"redirectUris\":[\"https://auth.aws-jupyter.example.org/oauth2/callback\",\"https://auth.aws-jupyter.example.org/.pomerium/signed_out\"]}");
            } else if (call.equals("PUT /admin/realms/polaris/clients/the-id")) {
                putBody = body;
                reply(exchange, updateCode, "{\"errorMessage\":\"refused\"}");
            } else {
                reply(exchange, 404, "");
            }
        });
        keycloak.start();
        provider = new KeyCloakProvider("http://localhost:" + keycloak.getAddress().getPort(), "admin", "admin");
    }

    @AfterEach
    void stopKeyCloak() {
        keycloak.stop(0);
    }

    @Test
    void failsTheSetupIfAnExistingClientCannotBeUpdated() {
        updateCode = 500;

        assertThatThrownBy(() -> provider.createBrowserClient("token", "polaris", "emrys", "secret", URIS))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Could not update the browser client emrys of realm polaris")
            .hasMessageContaining("HTTP 500");
        assertThat(requests).contains("PUT /admin/realms/polaris/clients/the-id");
    }

    @Test
    void failsTheSetupIfAnExistingClientCannotBeLookedUp() {
        found = false;

        assertThatThrownBy(() -> provider.createBrowserClient("token", "polaris", "emrys", "secret", URIS))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Could not update the browser client emrys of realm polaris: it could not be looked up");
        assertThat(requests).doesNotContain("PUT /admin/realms/polaris/clients/the-id");
    }

    @Test
    void failsTheSetupIfTheClientCannotBeCreated() {
        createCode = 500;

        assertThatThrownBy(() -> provider.createBrowserClient("token", "polaris", "emrys", "secret", URIS))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Could not create KeyCloak browser client emrys")
            .hasMessageContaining("HTTP 500");
    }

    @Test
    void readsTheRedirectUrisItOverwritesBeforeItDoes() throws IOException {
        try {
            provider.createBrowserClient("token", "polaris", "emrys", "secret", URIS);
        } catch (IOException | RuntimeException e) {
            // the mappers that follow are not what this is about
        }

        assertThat(requests).containsSubsequence("GET /admin/realms/polaris/clients/the-id", "PUT /admin/realms/polaris/clients/the-id");
    }

    @Test
    void replacesTheRedirectUrisOfAnExistingClientUnlessToldToKeepThem() {
        try {
            provider.createBrowserClient("token", "polaris", "emrys", "secret", URIS, RedirectStrategy.REPLACE);
        } catch (IOException | RuntimeException e) {
            // the mappers that follow are not what this is about
        }

        assertThat(putBody).contains(URIS.get(0), URIS.get(1), URIS.get(2), "\"post.logout.redirect.uris\":\"+\"").doesNotContain("aws-jupyter.example.org");
    }

    @Test
    void keepsTheRedirectUrisOfAnExistingClientAndAddsItsOwnIfToldTo() {
        try {
            provider.createBrowserClient("token", "polaris", "emrys", "secret", URIS, RedirectStrategy.MERGE);
        } catch (IOException | RuntimeException e) {
            // the mappers that follow are not what this is about
        }

        // the callbacks of another cloud's Jupyter stay, with this one's (including signed_out) and post.logout.redirect.uris '+'
        assertThat(putBody).contains("https://auth.aws-jupyter.example.org/oauth2/callback",
                                     "https://auth.aws-jupyter.example.org/.pomerium/signed_out",
                                     URIS.get(0), URIS.get(1), URIS.get(2),
                                     "\"post.logout.redirect.uris\":\"+\"");
    }

    @Test
    void purgesTheRedirectUrisThatAreNotToStayAndChangesNothingElse() throws IOException {
        List<String> purged = provider.purgeBrowserClientRedirectUris("emrys", "secret", URIS, false);

        // the existing ones, of another cloud, are not among those to keep
        assertThat(purged).containsExactly("https://auth.aws-jupyter.example.org/oauth2/callback",
                                           "https://auth.aws-jupyter.example.org/.pomerium/signed_out");
        assertThat(putBody).contains("\"redirectUris\":[]").contains("\"clientId\":\"emrys\"", "\"post.logout.redirect.uris\":\"+\"");
    }

    @Test
    void keepsWhatIsToStayWhenPurging() throws IOException {
        List<String> keep = new ArrayList<>(URIS);
        keep.add("https://auth.aws-jupyter.example.org/oauth2/callback");
        keep.add("https://auth.aws-jupyter.example.org/.pomerium/signed_out");

        assertThat(provider.purgeBrowserClientRedirectUris("emrys", "secret", keep, false)).isEmpty();
        assertThat(putBody).isNull();
    }

    @Test
    void aDryRunPurgeOnlySaysWhatItWouldTakeOff() throws IOException {
        assertThat(provider.purgeBrowserClientRedirectUris("emrys", "secret", URIS, true))
            .containsExactly("https://auth.aws-jupyter.example.org/oauth2/callback",
                             "https://auth.aws-jupyter.example.org/.pomerium/signed_out");
        assertThat(putBody).isNull();
    }

    @Test
    void aPurgeThatCannotUpdateTheClientFails() {
        updateCode = 500;

        assertThatThrownBy(() -> provider.purgeBrowserClientRedirectUris("emrys", "secret", URIS, false))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Could not purge the redirect URIs of the browser client emrys");
    }

    @Test
    void aPurgeOfAClientThatIsNotThereHasNothingToTakeOff() throws IOException {
        found = false;

        assertThat(provider.purgeBrowserClientRedirectUris("emrys", "secret", URIS, false)).isEmpty();
    }

    private static void reply(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, code == 204 ? -1 : bytes.length);
        if (code != 204) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
