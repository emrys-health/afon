package health.emrys.afon.cdk.configs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.AppConfig;
import com.hashicorp.cdktf.TerraformStack;
import health.emrys.afon.cdk.DockerImageResolver;
import health.emrys.afon.cdk.DockerImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PomeriumConfigTest {

    private static final String ISSUER  = "https://id.example.org/realms/polaris";
    private static final String DOMAIN  = "jupyter.example.org";
    private static final String CLIENT  = "tre-browser";
    private static final String SECRET  = "client-secret:with:colons";
    private static final String SHARED  = "c2hhcmVkLXNlY3JldA==";
    private static final String COOKIE  = "Y29va2llLXNlY3JldA==";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseYaml(String yaml) {
        return new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatesValidConfigWithOrderedRoutesAndMemberPolicy() {
        String yaml = PomeriumConfig.configYaml(
            JupyterHubConfig.MFA_UPSTREAMS, ISSUER, DOMAIN, CLIENT, SECRET, SHARED, COOKIE,
            List.of("Carol@Example.org", "alice@example.org", "alice@example.org"));

        Map<String, Object> root = parseYaml(yaml);
        assertThat(root.get("address")).isEqualTo(":8080");
        assertThat(root.get("insecure_server")).isEqualTo(true);
        assertThat(root.get("authenticate_service_url")).isEqualTo("https://auth." + DOMAIN);
        assertThat(root.get("idp_provider")).isEqualTo("oidc");
        assertThat(root.get("idp_provider_url")).isEqualTo(ISSUER);
        assertThat(root.get("idp_client_id")).isEqualTo(CLIENT);
        assertThat(root.get("idp_client_secret")).isEqualTo(SECRET);
        assertThat(root.get("idp_scopes")).isEqualTo(List.of("openid", "email", "profile"));
        assertThat(root.get("shared_secret")).isEqualTo(SHARED);
        assertThat(root.get("cookie_secret")).isEqualTo(COOKIE);
        assertThat(root.get("cookie_expire")).isEqualTo("12h");
        assertThat(root.get("xff_num_trusted_hops")).isEqualTo(1);
        assertThat(root.get("jwt_claims_headers")).isEqualTo(Map.of("X-Pomerium-Claim-Email", "email"));

        List<Map<String, Object>> routes = (List<Map<String, Object>>) root.get("routes");
        assertThat(routes).hasSize(3);

        // 1. /hub/admin deny route first, no policy
        Map<String, Object> admin = routes.get(0);
        assertThat(admin.get("from")).isEqualTo("https://" + DOMAIN);
        assertThat(admin.get("regex")).isEqualTo(PomeriumConfig.ADMIN_PATH_REGEX);
        assertThat(admin.get("to")).isEqualTo("http://proxy-public:8000/");
        assertThat(admin).doesNotContainKey("policy");

        // 2. /guacamole/ route with identity headers and sorted members
        Map<String, Object> guacamole = routes.get(1);
        assertThat(guacamole.get("from")).isEqualTo("https://" + DOMAIN);
        assertThat(guacamole.get("prefix")).isEqualTo("/guacamole/");
        assertThat(guacamole.get("to")).isEqualTo("http://guacamole-web:8080/guacamole/");
        assertThat(guacamole.get("allow_websockets")).isEqualTo(true);
        assertThat(guacamole.get("pass_identity_headers")).isEqualTo(true);
        assertThat(guacamole.get("preserve_host_header")).isEqualTo(true);
        assertThat(guacamole.get("policy")).isEqualTo(List.of(Map.of("allow", Map.of("or", List.of(
            Map.of("email", Map.of("is", "alice@example.org")),
            Map.of("email", Map.of("is", "carol@example.org")))))));

        // 3. / route for JupyterHub without identity headers
        Map<String, Object> jupyter = routes.get(2);
        assertThat(jupyter.get("from")).isEqualTo("https://" + DOMAIN);
        assertThat(jupyter.get("prefix")).isEqualTo("/");
        assertThat(jupyter.get("to")).isEqualTo("http://proxy-public:8000/");
        assertThat(jupyter.get("allow_websockets")).isEqualTo(true);
        assertThat(jupyter).doesNotContainKey("pass_identity_headers");
        assertThat(jupyter.get("preserve_host_header")).isEqualTo(true);
        assertThat(jupyter.get("policy")).isEqualTo(guacamole.get("policy"));
    }

    @Test
    void memberOrderIsDeterministic() {
        String first = PomeriumConfig.configYaml(
            JupyterHubConfig.MFA_UPSTREAMS, ISSUER, DOMAIN, CLIENT, SECRET, SHARED, COOKIE,
            List.of("bob@example.org", "alice@example.org"));
        String second = PomeriumConfig.configYaml(
            JupyterHubConfig.MFA_UPSTREAMS, ISSUER, DOMAIN, CLIENT, SECRET, SHARED, COOKIE,
            List.of("alice@example.org", "bob@example.org"));
        assertThat(first).isEqualTo(second);
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyMemberListOmitsPolicySoEveryoneIsRefused() {
        String yaml = PomeriumConfig.configYaml(
            JupyterHubConfig.MFA_UPSTREAMS, ISSUER, DOMAIN, CLIENT, SECRET, SHARED, COOKIE, List.of());
        List<Map<String, Object>> routes = (List<Map<String, Object>>) parseYaml(yaml).get("routes");
        assertThat(routes).hasSize(3);
        for (Map<String, Object> route : routes) {
            assertThat(route).doesNotContainKey("policy");
        }
    }

    @Test
    void escapesSpecialYamlCharactersSafely() {
        String trickySecret = "abc: 'def' # comment\nnewline";
        String yaml = PomeriumConfig.configYaml(
            JupyterHubConfig.MFA_UPSTREAMS, ISSUER, DOMAIN, CLIENT, trickySecret, SHARED, COOKIE,
            List.of("user+tag@example.org"));
        Map<String, Object> root = parseYaml(yaml);
        assertThat(root.get("idp_client_secret")).isEqualTo(trickySecret);
    }

    @Test
    void adminPathRegexMatchesPathVariantsAndNotOtherHubPaths() {
        Pattern pattern = Pattern.compile(PomeriumConfig.ADMIN_PATH_REGEX);
        for (String blocked : List.of(
            "/hub/admin", "/HUB/ADMIN", "/Hub/Admin/", "//hub/admin",
            "/hub//admin", "/hub%2fadmin", "/hub%2Fadmin/users", "/hub/admin;foo=bar", "/hub/admin%2f")) {
            assertThat(pattern.matcher(blocked).matches()).as("should match %s", blocked).isTrue();
        }
        for (String allowed : List.of("/", "/hub/home", "/hub/spawn", "/hub/login", "/hub/oauth_callback", "/guacamole/")) {
            assertThat(pattern.matcher(allowed).matches()).as("should not match %s", allowed).isFalse();
        }
    }

    @Test
    void synthesisesDeploymentWithRecreateProbesResourcesAndSecretVolume(@TempDir Path outDir) throws Exception {
        App app = new App(AppConfig.builder().outdir(outDir.toString()).skipValidation(true).build());
        TerraformStack stack = new TerraformStack(app, "test");
        new PomeriumConfig(stack, List.of(), DockerImageResolver.asDefined(),
                           JupyterHubConfig.MFA_UPSTREAMS, ISSUER, DOMAIN, CLIENT, SECRET, SHARED, COOKIE,
                           List.of("alice@example.org"));
        app.synth();

        JsonNode root = new ObjectMapper().readTree(
            Files.readString(outDir.resolve("stacks/test/cdk.tf.json")));
        JsonNode deployment = root.path("resource").path("kubernetes_deployment").path("pomeriumDeployment");
        assertThat(deployment.path("metadata").path("name").asText()).isEqualTo("pomerium-deployment");
        assertThat(deployment.path("spec").path("strategy").path("type").asText()).isEqualTo("Recreate");

        JsonNode podSpec = deployment.path("spec").path("template").path("spec");
        JsonNode container = podSpec.path("container").get(0);
        assertThat(container.path("name").asText()).isEqualTo("pomerium");
        assertThat(container.path("image").asText()).isEqualTo(DockerImages.POMERIUM_IMAGE);
        assertThat(container.has("args")).isFalse();
        assertThat(container.path("readiness_probe").path("http_get").path("path").asText()).isEqualTo("/ping");
        assertThat(container.path("liveness_probe").path("http_get").path("path").asText()).isEqualTo("/ping");
        assertThat(container.path("resources").path("requests").path("cpu").asText()).isEqualTo("100m");
        assertThat(container.path("resources").path("requests").path("memory").asText()).isEqualTo("256Mi");
        assertThat(container.path("resources").path("limits").path("memory").asText()).isEqualTo("512Mi");

        JsonNode mount = container.path("volume_mount").get(0);
        assertThat(mount.path("mount_path").asText()).isEqualTo("/pomerium");
        assertThat(mount.path("read_only").asBoolean()).isTrue();
        assertThat(mount.has("sub_path")).isFalse();

        assertThat(podSpec.path("init_container").get(0).path("name").asText()).isEqualTo("wait-for-issuer");

        JsonNode service = root.path("resource").path("kubernetes_service").path("pomerium");
        assertThat(service.path("spec").path("type").asText()).isEqualTo("NodePort");
        assertThat(service.path("spec").path("port").get(0).path("port").asInt()).isEqualTo(8080);
    }
}
