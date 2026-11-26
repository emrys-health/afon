package health.emrys.afon.security.keycloak;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import health.emrys.afon.project.TreProjectStack;
import health.emrys.afon.project.FileProjectStackProvider;
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
 * A project stack is made in the central identity by a client per project stack, whose token names that project stack only: the requests that
 * do it, against a KeyCloak that answers as the real one does.
 */
class KeyCloakProviderProjectTest {

    private static final String SECRET = "s3cr3t-of-the-client";

    /**
     * The project stack is a template, which every test renders for the cloud it is run on: {@code ${cloud}} is the cloud in lower case and
     * {@code ${CLOUD}} in upper case.  No cloud is special, so every test is run on every cloud.
     */
    private static final String TEMPLATE = """
        projectStack: {id: xyz-yyyy-nnnn, cloud: ${cloud}}
        identity: {issuerUrl: "https://id.example.org/realms/polaris", acr: mfa}
        dataProviders:
          defined:
            - {id: data1, catalogUri: "https://p.example/api", catalogName: w, audience: provider-data1, bucket: b}
        members: [ada@example.org]
        stacks:
          create: {auditBucket: audit, gitBucket: gitea}
          git: {domain: git.example.org}
          tre: {domains: {${CLOUD}_JUPYTER_DOMAIN_NAME: jupyter.example.org}}
        """;

    private static TreProjectStack projectStack(AfonCloud cloud) {
        return (TreProjectStack) FileProjectStackProvider.parse(render(TEMPLATE, cloud));
    }

    private static String render(String template, AfonCloud cloud) {
        return template.replace("${cloud}", cloud.toString().toLowerCase(java.util.Locale.ROOT)).replace("${CLOUD}", cloud.name());
    }

    /** The requests received, as {@code METHOD path}, and their bodies. */
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile boolean groupMade;
    private final FlowEmulator flows = new FlowEmulator();
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
            FlowEmulator.Answer flow = flows.handle(exchange.getRequestMethod(), path, body);
            if (flow != null) {
                reply(exchange, flow.code(), flow.body());
            } else if (path.endsWith("/protocol/openid-connect/token")) {
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
        provider.ensureProject(projectStack(cloud), Map.of("tre", SECRET, "git", SECRET));

        String treClient = bodyOf("POST /admin/realms/polaris/clients", "xyz-yyyy-nnnn-tre");
        assertThat(treClient).contains("\"clientAuthenticatorType\":\"client-secret\"", "\"secret\":\"" + SECRET + "\"", "\"fullScopeAllowed\":false",
                                       "\"directAccessGrantsEnabled\":false", "\"serviceAccountsEnabled\":false",
                                       "https://jupyter.example.org/hub/oauth_callback");
        assertThat(treClient).doesNotContain("jwks");
        // the home pages of the clients, which the page that ends an invitation links back to
        assertThat(treClient).contains("\"baseUrl\":\"https://jupyter.example.org/\"");
        assertThat(bodyOf("POST /admin/realms/polaris/clients", "xyz-yyyy-nnnn-git")).contains("\"baseUrl\":\"https://git.example.org/\"");
        // PKCE is allowed and not required, so that the logins of JupyterHub, which sends none, are not refused, and a client made before as requiring it is changed
        assertThat(treClient).contains("\"pkce.code.challenge.method\":\"\"").doesNotContain("S256");
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
        // the Git client carries no Data Provider audience
        assertThat(bodies.stream().filter(b -> b.contains("provider-data1"))).hasSize(1);

        assertThat(requests).contains("POST /admin/realms/polaris/groups",
                                      "POST /admin/realms/polaris/groups/gid/role-mappings/clients/uuid-xyz-yyyy-nnnn-tre",
                                      "POST /admin/realms/polaris/groups/gid/role-mappings/clients/uuid-xyz-yyyy-nnnn-git");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void makesOnlyTheTreClientForAProjectWithNoGitOfItsOwn(AfonCloud cloud) {
        TreProjectStack noGit = (TreProjectStack) FileProjectStackProvider.parse(render(TEMPLATE, cloud).replace(", gitBucket: gitea", "")
                                                                           .replace("  git: {domain: git.example.org}\n", ""));
        provider.ensureProject(noGit, Map.of("tre", SECRET));
        bodyOf("POST /admin/realms/polaris/clients", "xyz-yyyy-nnnn-tre");
        assertThat(bodies.stream().filter(b -> b.contains("xyz-yyyy-nnnn-git"))).isEmpty();
        assertThat(requests).contains("POST /admin/realms/polaris/groups/gid/role-mappings/clients/uuid-xyz-yyyy-nnnn-tre")
                            .doesNotContain("POST /admin/realms/polaris/groups/gid/role-mappings/clients/uuid-xyz-yyyy-nnnn-git");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void makesTheTreRedirectUrisFromTheJupyterDomainOfTheProjectsCloud(AfonCloud cloud) {
        assertThat(KeyCloakProvider.treRedirectUris(projectStack(cloud), null)).containsExactly(
            "https://jupyter.example.org/oauth2/callback", "https://jupyter.example.org/hub/oauth_callback");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAClientWithoutASecret(AfonCloud cloud) {
        assertThatThrownBy(() -> provider.ensureProject(projectStack(cloud), Map.of("tre", SECRET, "git", " ")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No secret for the git client");
        assertThatThrownBy(() -> provider.ensureProject(projectStack(cloud), Map.of("git", SECRET)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No secret for the tre client");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesASecretThatKeycloakCannotHold(AfonCloud cloud) {
        assertThatThrownBy(() -> provider.ensureProject(projectStack(cloud), Map.of("tre", "x".repeat(256), "git", SECRET)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("256 characters").hasMessageContaining("at most 255");
        provider.ensureProject(projectStack(cloud), Map.of("tre", "x".repeat(255), "git", SECRET));
    }

    private static health.emrys.afon.config.AfonPropertyProvider configWith(java.nio.file.Path dir, String afon) throws IOException {
        java.nio.file.Files.writeString(dir.resolve("afon.properties"), afon);
        return new health.emrys.afon.config.FilePropertyProvider(dir.toString(), List.of("afon"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesTheJupyterDomainOfTheConfigWhenTheFileGivesNone(AfonCloud cloud, @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws IOException {
        TreProjectStack noDomain = (TreProjectStack) FileProjectStackProvider.parse(render(TEMPLATE, cloud).replace("tre: {domains: {${CLOUD}_JUPYTER_DOMAIN_NAME: jupyter.example.org}}".replace("${CLOUD}", cloud.name()), "tre: {}"));
        var config = configWith(dir, cloud.name() + "_JUPYTER_DOMAIN_NAME=jupyter.derived.example.org\n");

        assertThat(KeyCloakProvider.treRedirectUris(noDomain, config)).containsExactly(
            "https://jupyter.derived.example.org/oauth2/callback", "https://jupyter.derived.example.org/hub/oauth_callback");
        // the file's own domain wins over the config's, and a blank one in the config is none
        assertThat(KeyCloakProvider.treRedirectUris(projectStack(cloud), config)).first().asString().contains("jupyter.example.org");
        assertThatThrownBy(() -> KeyCloakProvider.treRedirectUris(noDomain, configWith(dir, cloud.name() + "_JUPYTER_DOMAIN_NAME= \n")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(cloud.name() + "_JUPYTER_DOMAIN_NAME").hasMessageContaining("config");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAProjectWithoutAJupyterDomainForItsRedirectUris(AfonCloud cloud) {
        TreProjectStack noDomain = (TreProjectStack) FileProjectStackProvider.parse(render(TEMPLATE, cloud).replace("tre: {domains: {${CLOUD}_JUPYTER_DOMAIN_NAME: jupyter.example.org}}".replace("${CLOUD}", cloud.name()), "tre: {}"));
        assertThatThrownBy(() -> provider.ensureProject(noDomain, Map.of("tre", SECRET, "git", SECRET)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(cloud.name() + "_JUPYTER_DOMAIN_NAME");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void addsAMemberToTheGroup(AfonCloud cloud) {
        groupMade = true;
        provider.addMember(projectStack(cloud), "the-user-id");
        assertThat(requests).contains("PUT /admin/realms/polaris/users/the-user-id/groups/gid");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void removesAMemberFromTheGroupAndEndsTheirSessions(AfonCloud cloud) {
        groupMade = true;
        provider.removeMember(projectStack(cloud), "the-user-id");
        assertThat(requests).contains("DELETE /admin/realms/polaris/users/the-user-id/groups/gid",
                                      "POST /admin/realms/polaris/users/the-user-id/logout");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void failsToAddAMemberToAProjectThatIsNotMade(AfonCloud cloud) {
        assertThatThrownBy(() -> provider.addMember(projectStack(cloud), "the-user-id"))
            .hasRootCauseMessage("KeyCloak has no group for project stack xyz-yyyy-nnnn: make it with manage-identity create");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void closesTheTreClientOnly(AfonCloud cloud) {
        provider.closeProject(projectStack(cloud));
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

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void usesTheUserWhoHasTheEmailUnderAnotherUsernameInsteadOfMakingAnother(AfonCloud cloud) {
        // an admin of users.csv, whose username is not their email
        keycloak.removeContext("/");
        keycloak.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String call = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            String query = String.valueOf(exchange.getRequestURI().getQuery());
            requests.add(call);
            bodies.add(body);
            if (exchange.getRequestURI().getPath().endsWith("/protocol/openid-connect/token")) {
                reply(exchange, 200, "{\"access_token\":\"the-token\"}");
            } else if (call.equals("GET /admin/realms/polaris/users")) {
                reply(exchange, 200, query.contains("email=") ? "[{\"id\":\"the-admin-id\"}]" : "[]");
            } else if (call.equals("GET /admin/realms/polaris/users/profile")) {
                reply(exchange, 200, "{\"attributes\":[{\"name\":\"username\"}]}");
            } else if (call.equals("GET /admin/realms/polaris/users/the-admin-id")) {
                reply(exchange, 200, "{\"username\":\"ada\"}");
            } else {
                reply(exchange, call.startsWith("POST") ? 201 : 204, "");
            }
        });
        assertThat(provider.ensureUser("Ada@Example.org", "Ada", "L")).isEqualTo("the-admin-id");
        assertThat(requests).doesNotContain("POST /admin/realms/polaris/users");
        assertThat(requests).doesNotContain("PUT /admin/realms/polaris/users/the-admin-id/execute-actions-email");
        assertThat(bodyOf("PUT /admin/realms/polaris/users/the-admin-id", "afon:the-admin-id")).contains("polaris_principal");
    }

    private Map<String, Object> childOf(String flow, String displayNameOrProvider) {
        return flows.children(flow).stream()
                    .filter(e -> displayNameOrProvider.equals(e.get("displayName")) || displayNameOrProvider.equals(e.get("providerId")))
                    .findFirst().orElseThrow(() -> new AssertionError("No " + displayNameOrProvider + " in " + flow + ": " + flows.flows));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void logsTheMembersInToTheClientAfterTheSecondFactorAndNoOneElse(AfonCloud cloud) {
        provider.ensureProject(projectStack(cloud), Map.of("tre", SECRET, "git", SECRET));

        String tre = "xyz-yyyy-nnnn-tre";
        // the login is a flow of the client's own: the cookie or the forms, and then the check of membership, whichever it was
        assertThat(childOf(tre + "-browser", tre + "-authenticate").get("requirement")).isEqualTo("REQUIRED");
        assertThat(childOf(tre + "-browser", tre + "-members").get("requirement")).isEqualTo("CONDITIONAL");
        assertThat(childOf(tre + "-authenticate", "auth-cookie").get("requirement")).isEqualTo("ALTERNATIVE");
        assertThat(childOf(tre + "-authenticate", tre + "-forms").get("requirement")).isEqualTo("ALTERNATIVE");
        // the first factor, then the second, each when the session is below its level
        assertThat(childOf(tre + "-forms", tre + "-level-1").get("requirement")).isEqualTo("CONDITIONAL");
        assertThat(childOf(tre + "-level-1", "auth-username-password-form").get("requirement")).isEqualTo("REQUIRED");
        assertThat(flows.configOf(childOf(tre + "-level-1", "conditional-level-of-authentication")).get("config"))
            .isEqualTo(Map.of("loa-condition-level", "1", "loa-max-age", "36000"));
        assertThat(childOf(tre + "-level-2", "auth-otp-form").get("requirement")).isEqualTo("REQUIRED");
        assertThat(flows.configOf(childOf(tre + "-level-2", "conditional-level-of-authentication")).get("config"))
            .isEqualTo(Map.of("loa-condition-level", "2", "loa-max-age", "36000"));
        // whoever lacks the client's role of the project stack is denied
        assertThat(flows.configOf(childOf(tre + "-members", "conditional-user-role")).get("config"))
            .isEqualTo(Map.of("condUserRole", tre + ".xyz-yyyy-nnnn", "negate", "true"));
        assertThat(childOf(tre + "-members", "conditional-user-role").get("requirement")).isEqualTo("REQUIRED");
        assertThat(childOf(tre + "-members", "deny-access-authenticator").get("requirement")).isEqualTo("REQUIRED");
        // the Git client has its own, that checks its own role
        assertThat(flows.configOf(childOf("xyz-yyyy-nnnn-git-members", "conditional-user-role")).get("config"))
            .isEqualTo(Map.of("condUserRole", "xyz-yyyy-nnnn-git.xyz-yyyy-nnnn", "negate", "true"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void bindsTheLoginAndTheAcrToEachClientInTheSameUpdateThatMakesIt(AfonCloud cloud) {
        provider.ensureProject(projectStack(cloud), Map.of("tre", SECRET, "git", SECRET));

        assertThat(bodyOf("POST /admin/realms/polaris/clients", "\"clientId\":\"xyz-yyyy-nnnn-tre\"")).contains(
            "\"authenticationFlowBindingOverrides\":{\"browser\":\"flow-xyz-yyyy-nnnn-tre-browser\"}", "\"default.acr.values\":\"mfa\"", "\"minimum.acr.value\":\"mfa\"");
        assertThat(bodyOf("POST /admin/realms/polaris/clients", "\"clientId\":\"xyz-yyyy-nnnn-git\"")).contains(
            "\"authenticationFlowBindingOverrides\":{\"browser\":\"flow-xyz-yyyy-nnnn-git-browser\"}");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void mapsTheAcrToTheLevelOfTheSecondFactorKeepingTheOtherMappings(AfonCloud cloud) {
        provider.ensureProject(projectStack(cloud), Map.of("tre", SECRET, "git", SECRET));

        assertThat(bodyOf("PUT /admin/realms/polaris", "acr.loa.map")).contains("acr.loa.map", "mfa", "2");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void convergesTheLoginAgainWithoutMakingAnotherOrLeavingItOpen(AfonCloud cloud) {
        provider.ensureProject(projectStack(cloud), Map.of("tre", SECRET, "git", SECRET));
        int executions = flows.flows.values().stream().mapToInt(List::size).sum();
        int configs = flows.configs.size();

        provider.ensureProject(projectStack(cloud), Map.of("tre", SECRET, "git", SECRET));

        assertThat(flows.flows.values().stream().mapToInt(List::size).sum()).isEqualTo(executions);
        assertThat(flows.configs).hasSize(configs);
        assertThat(childOf("xyz-yyyy-nnnn-tre-members", "deny-access-authenticator").get("requirement")).isEqualTo("REQUIRED");
        assertThat(requests).doesNotContain("DELETE /admin/realms/polaris/authentication/flows/flow-xyz-yyyy-nnnn-tre-browser");
    }

    /** A KeyCloak that has the user of the email, or none, and answers the sending of the invitation with the code. */
    private void keycloakWith(boolean userExists, int invitationCode) {
        keycloak.removeContext("/");
        keycloak.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String call = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            requests.add(call);
            bodies.add(body);
            if (exchange.getRequestURI().getPath().endsWith("/protocol/openid-connect/token")) {
                reply(exchange, 200, "{\"access_token\":\"the-token\"}");
            } else if (call.equals("GET /admin/realms/polaris/users")) {
                reply(exchange, 200, userExists ? "[{\"id\":\"the-user-id\"}]" : "[]");
            } else if (call.equals("PUT /admin/realms/polaris/users/the-user-id/execute-actions-email")) {
                reply(exchange, invitationCode, "");
            } else {
                reply(exchange, 404, "");
            }
        });
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void invitesAnExistingUserAgainWithTheActionsOfTheInvitationAndMakesNoOther(AfonCloud cloud) {
        keycloakWith(true, 204);

        provider.invite("Alice@Tre.Example");

        assertThat(bodyOf("PUT /admin/realms/polaris/users/the-user-id/execute-actions-email", "VERIFY_EMAIL"))
            .contains("UPDATE_PASSWORD", "CONFIGURE_TOTP");
        assertThat(requests).doesNotContain("POST /admin/realms/polaris/users");
    }

    /** The queries of the invitations sent, as they were received: empty for one with none. */
    private final List<String> invitationQueries = new CopyOnWriteArrayList<>();

    /** A KeyCloak that has the user, and the client or not, and answers an invitation that names the client with the code, and any other with 204. */
    private void keycloakWithClient(boolean clientExists, int codeWithClient) {
        keycloak.removeContext("/");
        keycloak.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            // as it was sent, not decoded
            String query = exchange.getRequestURI().getRawQuery();
            String call = exchange.getRequestMethod() + " " + path;
            requests.add(call);
            if (path.endsWith("/protocol/openid-connect/token")) {
                reply(exchange, 200, "{\"access_token\":\"the-token\"}");
            } else if (call.equals("GET /admin/realms/polaris/users")) {
                reply(exchange, 200, "[{\"id\":\"the-user-id\"}]");
            } else if (call.equals("GET /admin/realms/polaris/clients")) {
                reply(exchange, 200, clientExists ? "[{\"id\":\"uuid\"}]" : "[]");
            } else if (call.equals("PUT /admin/realms/polaris/users/the-user-id/execute-actions-email")) {
                invitationQueries.add(query == null ? "" : query);
                reply(exchange, query != null && query.contains("client_id=") ? codeWithClient : 204, "");
            } else {
                reply(exchange, 404, "");
            }
        });
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void theInvitationNamesTheClientSoThatItsFinalPageLinksBackToIt(AfonCloud cloud) {
        keycloakWithClient(true, 204);

        provider.invite("alice@tre.example", "xyz-yyyy-nnnn-tre");

        // only the client, and no redirect_uri, which the client would have to register
        assertThat(invitationQueries).containsExactly("client_id=xyz-yyyy-nnnn-tre");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void theInvitationNamesNoClientWhenNoneIsGivenOrTheClientIsNotThere(AfonCloud cloud) {
        keycloakWithClient(false, 204);

        provider.invite("alice@tre.example", "xyz-yyyy-nnnn-tre");
        provider.invite("alice@tre.example", null);
        provider.invite("alice@tre.example");

        assertThat(invitationQueries).containsExactly("", "", "");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void theInvitationIsSentWithoutTheClientWhenTheIdentityRefusesItWithTheClient(AfonCloud cloud) {
        // eg the client is disabled: the invitation must still be sent
        keycloakWithClient(true, 400);

        provider.invite("alice@tre.example", "xyz-yyyy-nnnn-tre");

        assertThat(invitationQueries).containsExactly("client_id=xyz-yyyy-nnnn-tre", "");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void theClientIdOfTheInvitationIsEncoded(AfonCloud cloud) {
        keycloakWithClient(true, 204);

        provider.invite("alice@tre.example", "a b&c");

        assertThat(invitationQueries).containsExactly("client_id=a+b%26c");
    }

    /** A KeyCloak that has the user of the email, or none, with the credentials, which the removal of one is answered with 204. */
    private void keycloakWithCredentials(boolean userExists, String credentials) {
        keycloak.removeContext("/");
        keycloak.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String call = exchange.getRequestMethod() + " " + path;
            requests.add(call);
            if (path.endsWith("/protocol/openid-connect/token")) {
                reply(exchange, 200, "{\"access_token\":\"the-token\"}");
            } else if (call.equals("GET /admin/realms/polaris/users")) {
                reply(exchange, 200, userExists ? "[{\"id\":\"the-user-id\"}]" : "[]");
            } else if (call.equals("GET /admin/realms/polaris/users/the-user-id/credentials")) {
                reply(exchange, 200, credentials);
            } else {
                reply(exchange, call.startsWith("POST") ? 201 : 204, "");
            }
        });
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void removesTheAuthenticatorsOfAUserAndEndsTheirSessionsAndKeepsTheirPassword(AfonCloud cloud) {
        keycloakWithCredentials(true, "[{\"id\":\"c1\",\"type\":\"password\"},{\"id\":\"c2\",\"type\":\"otp\"},{\"id\":\"c3\",\"type\":\"otp\"}]");

        assertThat(provider.removeSecondFactor("Alice@Tre.Example")).isEqualTo(2);

        assertThat(requests).contains("DELETE /admin/realms/polaris/users/the-user-id/credentials/c2",
                                      "DELETE /admin/realms/polaris/users/the-user-id/credentials/c3",
                                      "POST /admin/realms/polaris/users/the-user-id/logout");
        assertThat(requests).doesNotContain("DELETE /admin/realms/polaris/users/the-user-id/credentials/c1");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void removesNothingAndEndsNoSessionsOfAUserWithNoAuthenticator(AfonCloud cloud) {
        keycloakWithCredentials(true, "[{\"id\":\"c1\",\"type\":\"password\"}]");

        assertThat(provider.removeSecondFactor("alice@tre.example")).isZero();

        assertThat(requests).noneMatch(call -> call.startsWith("DELETE") || call.endsWith("/logout"));
    }

    /**
     * A KeyCloak that has the user of the email, or none, with the credentials, and the client {@code xyz-yyyy-nnnn-tre} with the redirect URIs, answering what
     * is asked of it to sign a member in to, and recording the calls with their bodies.
     */
    /** What the identity has of the user of the sign-in tests. */
    private String userRepresentation = "{\"id\":\"the-user-id\"}";

    private void keycloakForSigningIn(boolean userExists, String credentials, int passwordCode) {
        keycloak.removeContext("/");
        keycloak.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String call = exchange.getRequestMethod() + " " + path;
            String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            requests.add(call);
            bodies.add(body);
            if (path.endsWith("/protocol/openid-connect/token")) {
                reply(exchange, 200, "{\"access_token\":\"the-token\"}");
            } else if (call.equals("GET /admin/realms/polaris/users")) {
                reply(exchange, 200, userExists ? "[{\"id\":\"the-user-id\"}]" : "[]");
            } else if (call.equals("GET /admin/realms/polaris/users/the-user-id")) {
                reply(exchange, 200, userRepresentation);
            } else if (call.equals("GET /admin/realms/polaris/users/the-user-id/credentials")) {
                reply(exchange, 200, credentials);
            } else if (call.equals("PUT /admin/realms/polaris/users/the-user-id/reset-password")) {
                reply(exchange, passwordCode, "");
            } else if (call.equals("GET /admin/realms/polaris/clients")) {
                reply(exchange, 200, "xyz-yyyy-nnnn-tre".equals(exchange.getRequestURI().getQuery().replace("clientId=", "")) ? "[{\"id\":\"the-client-uuid\"}]" : "[]");
            } else if (call.equals("GET /admin/realms/polaris/clients/the-client-uuid")) {
                reply(exchange, 200, "{\"redirectUris\":[\"https://jupyter.example.org/oauth2/callback\",\"https://jupyter.example.org/hub/oauth_callback\"]}");
            } else {
                reply(exchange, call.startsWith("POST") ? 201 : 204, "");
            }
        });
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void saysWhetherAUserHasAnAuthenticator(AfonCloud cloud) {
        keycloakForSigningIn(true, "[{\"id\":\"c1\",\"type\":\"password\"}]", 204);
        assertThat(provider.hasSecondFactor("Alice@Tre.Example")).isFalse();

        keycloakForSigningIn(true, "[{\"id\":\"c1\",\"type\":\"password\"},{\"id\":\"c2\",\"type\":\"otp\"}]", 204);
        assertThat(provider.hasSecondFactor("alice@tre.example")).isTrue();

        keycloakForSigningIn(false, "[]", 204);
        assertThatThrownBy(() -> provider.hasSecondFactor("nobody@tre.example")).isInstanceOf(IllegalStateException.class).hasMessageContaining("No user nobody@tre.example");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void setsAPasswordToStayAndReadiesTheAccountToSignInToWithNoActionLeftToDo(AfonCloud cloud) {
        keycloakForSigningIn(true, "[]", 204);

        provider.setPassword("Alice@Tre.Example", "the-new-password", "Smoke", "Tests");

        int account = requests.indexOf("PUT /admin/realms/polaris/users/the-user-id");
        int password = requests.indexOf("PUT /admin/realms/polaris/users/the-user-id/reset-password");
        assertThat(account).isNotNegative();
        assertThat(bodies.get(account)).contains("\"emailVerified\":true").contains("\"requiredActions\":[]");
        assertThat(password).isGreaterThan(account);
        assertThat(bodies.get(password)).contains("\"type\":\"password\"").contains("\"value\":\"the-new-password\"").contains("\"temporary\":false");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void givesTheNamesToAUserWhoHasNoneAndKeepsThoseThatTheyHave(AfonCloud cloud) {
        keycloakForSigningIn(true, "[]", 204);
        provider.setPassword("alice@tre.example", "pw", "Smoke", "Tests");
        assertThat(bodies.get(requests.indexOf("PUT /admin/realms/polaris/users/the-user-id"))).contains("\"firstName\":\"Smoke\"").contains("\"lastName\":\"Tests\"");

        requests.clear();
        bodies.clear();
        userRepresentation = "{\"id\":\"the-user-id\",\"firstName\":\"Alice\",\"lastName\":\"\"}";
        keycloakForSigningIn(true, "[]", 204);
        provider.setPassword("alice@tre.example", "pw", "Smoke", "Tests");
        assertThat(bodies.get(requests.indexOf("PUT /admin/realms/polaris/users/the-user-id"))).doesNotContain("firstName").contains("\"lastName\":\"Tests\"");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesToSetThePasswordOfAUserWhoIsNotInTheIdentityOrWhenTheIdentityRefuses(AfonCloud cloud) {
        keycloakForSigningIn(false, "[]", 204);
        assertThatThrownBy(() -> provider.setPassword("nobody@tre.example", "x", "A", "B")).isInstanceOf(IllegalStateException.class).hasMessageContaining("No user nobody@tre.example");

        keycloakForSigningIn(true, "[]", 400);
        assertThatThrownBy(() -> provider.setPassword("alice@tre.example", "x", "A", "B")).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Could not set the password").hasMessageContaining("HTTP 400").hasMessageNotContaining("the-new-password");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void givesTheRedirectUrisOfAClientOrNoneForAClientThatIsNotThere(AfonCloud cloud) {
        keycloakForSigningIn(true, "[]", 204);

        assertThat(provider.redirectUrisOf("xyz-yyyy-nnnn-tre")).containsExactly("https://jupyter.example.org/oauth2/callback", "https://jupyter.example.org/hub/oauth_callback");
        assertThat(provider.redirectUrisOf("no-such-client")).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesToRemoveTheAuthenticatorsOfAUserWhoIsNotInTheIdentity(AfonCloud cloud) {
        keycloakWithCredentials(false, "[]");

        assertThatThrownBy(() -> provider.removeSecondFactor("nobody@tre.example")).isInstanceOf(IllegalStateException.class)
                                                                                    .hasMessageContaining("No user nobody@tre.example");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesToInviteAUserWhoIsNotInTheIdentity(AfonCloud cloud) {
        keycloakWith(false, 204);

        assertThatThrownBy(() -> provider.invite("nobody@tre.example")).isInstanceOf(IllegalStateException.class)
                                                                         .hasMessageContaining("No user nobody@tre.example");
        assertThat(requests).doesNotContain("PUT /admin/realms/polaris/users/the-user-id/execute-actions-email");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void failsToInviteWhenTheEmailCannotBeSentUnlikeTheInvitationOfANewUser(AfonCloud cloud) {
        keycloakWith(true, 500);

        assertThatThrownBy(() -> provider.invite("alice@tre.example")).isInstanceOf(IllegalStateException.class)
                                                                       .hasMessageContaining("HTTP 500").hasMessageContaining("SMTP");
    }
}
