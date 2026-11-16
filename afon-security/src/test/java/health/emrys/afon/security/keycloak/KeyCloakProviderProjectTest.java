package health.emrys.afon.security.keycloak;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import health.emrys.afon.project.TreProject;
import health.emrys.afon.project.FileProjectProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import health.emrys.afon.config.AfonCloud;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A project is made in the central identity by a client per project, whose token names that project only: the requests that
 * do it, against a KeyCloak that answers as the real one does.
 */
class KeyCloakProviderProjectTest {

    private static final String JWK = "{\"kty\":\"RSA\",\"n\":\"abc\",\"e\":\"AQAB\"}";

    /**
     * The project is a template, which every test renders for the cloud it is run on: {@code ${cloud}} is the cloud in lower case and
     * {@code ${CLOUD}} in upper case.  No cloud is special, so every test is run on every cloud.
     */
    private static final String TEMPLATE = """
        project: {id: xyz-yyyy-nnnn, cloud: ${cloud}}
        identity: {issuerUrl: "https://id.example.org/realms/polaris", acr: mfa}
        providers:
          - {id: data1, catalogUri: "https://p.example/api", catalogName: w, audience: provider-data1, bucket: b}
        stacks:
          create: {auditBucket: audit, gitBucket: gitea}
          git: {domain: git.example.org}
          tre: {domains: {${CLOUD}_JUPYTER_DOMAIN_NAME: jupyter.example.org}}
        """;

    private static TreProject project(AfonCloud cloud) {
        return (TreProject) FileProjectProvider.parse(render(TEMPLATE, cloud));
    }

    private static String render(String template, AfonCloud cloud) {
        return template.replace("${cloud}", cloud.toString().toLowerCase(java.util.Locale.ROOT)).replace("${CLOUD}", cloud.name());
    }

    /** The requests received, as {@code METHOD path}, and their bodies. */
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile boolean groupMade;
    private HttpServer keycloak;
    private KeyCloakProvider provider;

    @BeforeEach
    void startKeyCloak() throws IOException {
        keycloak = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        keycloak.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            String call = exchange.getRequestMethod() + " " + path;
            requests.add(call);
            bodies.add(body);
            if (path.endsWith("/protocol/openid-connect/token")) {
                reply(exchange, 200, "{\"access_token\":\"the-token\"}");
            } else if (call.equals("GET /admin/realms/polaris/clients")) {
                String clientId = exchange.getRequestURI().getQuery().replace("clientId=", "");
                reply(exchange, 200, "[{\"id\":\"uuid-" + clientId + "\"}]");
            } else if (call.equals("GET /admin/realms/polaris/client-scopes")) {
                reply(exchange, 200, "[{\"id\":\"roles-id\",\"name\":\"roles\"},{\"id\":\"email-id\",\"name\":\"email\"}]");
            } else if (call.equals("GET /admin/realms/polaris/groups")) {
                reply(exchange, 200, groupMade ? "[{\"id\":\"gid\",\"name\":\"xyz-yyyy-nnnn\"}]" : "[]");
            } else if (call.equals("POST /admin/realms/polaris/groups")) {
                groupMade = true;
                reply(exchange, 201, "");
            } else if (path.matches("/admin/realms/polaris/clients/[^/]+/roles/[^/]+") && call.startsWith("GET")) {
                reply(exchange, 200, "{\"id\":\"rid\",\"name\":\"xyz-yyyy-nnnn\"}");
            } else if (call.equals("GET /admin/realms/polaris/users")) {
                reply(exchange, 200, "[{\"id\":\"the-user-id\",\"username\":\"alice@tre.example\"}]");
            } else if (call.startsWith("GET")) {
                reply(exchange, 200, "{}".equals(path) ? "{}" : (path.endsWith("/models") ? "[]" : "{\"attributes\":{}}"));
            } else {
                reply(exchange, call.startsWith("POST") ? 201 : 204, "");
            }
        });
        keycloak.start();
        provider = new KeyCloakProvider("http://localhost:" + keycloak.getAddress().getPort(), "admin", "admin");
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

    private String bodyOf(String call, String containing) {
        for (int i = 0; i < requests.size(); i++) {
            if (requests.get(i).equals(call) && bodies.get(i).contains(containing)) {
                return bodies.get(i);
            }
        }
        throw new AssertionError("No " + call + " with " + containing + " among " + requests);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void makesTheTwoClientsTheGroupAndTheClaims(AfonCloud cloud) {
        provider.ensureProject(project(cloud), Map.of("tre", JWK, "git", JWK));

        String treClient = bodyOf("POST /admin/realms/polaris/clients", "xyz-yyyy-nnnn-tre");
        assertThat(treClient).contains("\"clientAuthenticatorType\":\"client-jwt\"", "\"fullScopeAllowed\":false",
                                       "\"directAccessGrantsEnabled\":false", "\"serviceAccountsEnabled\":false",
                                       "https://jupyter.example.org/hub/oauth_callback", "use.jwks.string");
        // a single JWK is wrapped as a JWK set
        assertThat(treClient).contains("keys");
        assertThat(bodyOf("POST /admin/realms/polaris/clients", "xyz-yyyy-nnnn-git"))
            .contains("https://git.example.org/user/oauth2/keycloak/callback");
        assertThat(requests).contains("DELETE /admin/realms/polaris/clients/uuid-xyz-yyyy-nnnn-tre/default-client-scopes/roles-id",
                                      "DELETE /admin/realms/polaris/clients/uuid-xyz-yyyy-nnnn-git/default-client-scopes/roles-id");
        assertThat(requests).doesNotContain(
            "DELETE /admin/realms/polaris/clients/uuid-xyz-yyyy-nnnn-tre/default-client-scopes/email-id");

        // the project claim comes from the client's own roles, the audiences from mappers
        assertThat(bodyOf("POST /admin/realms/polaris/clients/uuid-xyz-yyyy-nnnn-tre/protocol-mappers/models", "client-role-mapper"))
            .contains("\"usermodel.clientRoleMapping.clientId\":\"xyz-yyyy-nnnn-tre\"", "\"claim.name\":\"project\"");
        bodyOf("POST /admin/realms/polaris/clients/uuid-xyz-yyyy-nnnn-tre/protocol-mappers/models", "provider-data1");
        // the Git client carries no Provider audience
        assertThat(bodies.stream().filter(b -> b.contains("provider-data1"))).hasSize(1);

        assertThat(requests).contains("POST /admin/realms/polaris/groups",
                                      "POST /admin/realms/polaris/groups/gid/role-mappings/clients/uuid-xyz-yyyy-nnnn-tre",
                                      "POST /admin/realms/polaris/groups/gid/role-mappings/clients/uuid-xyz-yyyy-nnnn-git");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void makesOnlyTheTreClientForAProjectWithNoGitOfItsOwn(AfonCloud cloud) {
        TreProject noGit = (TreProject) FileProjectProvider.parse(render(TEMPLATE, cloud).replace(", gitBucket: gitea", "")
                                                                           .replace("  git: {domain: git.example.org}\n", ""));
        provider.ensureProject(noGit, Map.of("tre", JWK));
        bodyOf("POST /admin/realms/polaris/clients", "xyz-yyyy-nnnn-tre");
        assertThat(bodies.stream().filter(b -> b.contains("xyz-yyyy-nnnn-git"))).isEmpty();
        assertThat(requests).contains("POST /admin/realms/polaris/groups/gid/role-mappings/clients/uuid-xyz-yyyy-nnnn-tre")
                            .doesNotContain("POST /admin/realms/polaris/groups/gid/role-mappings/clients/uuid-xyz-yyyy-nnnn-git");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void makesTheTreRedirectUrisFromTheJupyterDomainOfTheProjectsCloud(AfonCloud cloud) {
        assertThat(KeyCloakProvider.treRedirectUris(project(cloud))).containsExactly(
            "https://jupyter.example.org/oauth2/callback", "https://jupyter.example.org/hub/oauth_callback");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAKeyHoldingPrivateKeyMaterial(AfonCloud cloud) {
        assertThatThrownBy(() -> provider.ensureProject(project(cloud), Map.of("tre", JWK.replace("\"e\"", "\"d\":\"x\",\"e\""), "git", JWK)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("private key material");
        assertThat(requests).noneMatch(call -> call.startsWith("POST /admin/realms/polaris/clients"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAProjectWithoutAJupyterDomainForItsRedirectUris(AfonCloud cloud) {
        TreProject noDomain = (TreProject) FileProjectProvider.parse(render(TEMPLATE, cloud).replace("tre: {domains: {${CLOUD}_JUPYTER_DOMAIN_NAME: jupyter.example.org}}".replace("${CLOUD}", cloud.name()), "tre: {}"));
        assertThatThrownBy(() -> provider.ensureProject(noDomain, Map.of("tre", JWK, "git", JWK)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(cloud.name() + "_JUPYTER_DOMAIN_NAME");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void addsAMemberToTheGroup(AfonCloud cloud) {
        groupMade = true;
        provider.addMember(project(cloud), "the-user-id");
        assertThat(requests).contains("PUT /admin/realms/polaris/users/the-user-id/groups/gid");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void removesAMemberFromTheGroupAndEndsTheirSessions(AfonCloud cloud) {
        groupMade = true;
        provider.removeMember(project(cloud), "the-user-id");
        assertThat(requests).contains("DELETE /admin/realms/polaris/users/the-user-id/groups/gid",
                                      "POST /admin/realms/polaris/users/the-user-id/logout");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void failsToAddAMemberToAProjectThatIsNotMade(AfonCloud cloud) {
        assertThatThrownBy(() -> provider.addMember(project(cloud), "the-user-id"))
            .hasRootCauseMessage("KeyCloak has no group for project xyz-yyyy-nnnn: make it with manage-identity create");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void closesTheTreClientOnly(AfonCloud cloud) {
        provider.closeProject(project(cloud));
        assertThat(bodyOf("PUT /admin/realms/polaris/clients/uuid-xyz-yyyy-nnnn-tre", "\"enabled\":false")).isNotEmpty();
        assertThat(requests).doesNotContain("PUT /admin/realms/polaris/clients/uuid-xyz-yyyy-nnnn-git");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void makesAUserOnceWithTheInvitationAndAPrincipalOfTheirId(AfonCloud cloud) {
        // the user is not there until created
        List<String> users = new ArrayList<>();
        keycloak.removeContext("/");
        keycloak.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String call = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            requests.add(call);
            bodies.add(body);
            if (call.endsWith("/token") || exchange.getRequestURI().getPath().endsWith("/protocol/openid-connect/token")) {
                reply(exchange, 200, "{\"access_token\":\"the-token\"}");
            } else if (call.equals("GET /admin/realms/polaris/users")) {
                reply(exchange, 200, users.isEmpty() ? "[]" : "[{\"id\":\"the-user-id\"}]");
            } else if (call.equals("POST /admin/realms/polaris/users")) {
                users.add(body);
                reply(exchange, 201, "");
            } else if (call.equals("GET /admin/realms/polaris/users/profile")) {
                reply(exchange, 200, "{\"attributes\":[{\"name\":\"username\"}]}");
            } else if (call.equals("GET /admin/realms/polaris/users/the-user-id")) {
                reply(exchange, 200, "{\"username\":\"alice@tre.example\"}");
            } else {
                reply(exchange, call.startsWith("POST") ? 201 : 204, "");
            }
        });
        assertThat(provider.ensureUser("Alice@Tre.Example", "Alice", "A")).isEqualTo("the-user-id");
        assertThat(users).singleElement().satisfies(body -> assertThat(body).contains(
            "\"username\":\"alice@tre.example\"", "VERIFY_EMAIL", "UPDATE_PASSWORD", "CONFIGURE_TOTP").doesNotContain("password\""));
        assertThat(bodyOf("PUT /admin/realms/polaris/users/the-user-id", "afon:the-user-id")).contains("polaris_principal");
        assertThat(requests).contains("PUT /admin/realms/polaris/users/the-user-id/execute-actions-email");
        // KeyCloak drops an attribute that the user profile does not declare, so it is declared, for admins only
        assertThat(bodyOf("PUT /admin/realms/polaris/users/profile", "polaris_principal")).contains("\"edit\":[\"admin\"]");
    }
}
