package health.emrys.afon.cdk.configs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.cdk.SynthesisSupport;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A TRE that lists smoke members has, in its hub, a service for the smoke tests, with a token of its own, which the hub's secret holds, and a role that is only to read users
 * and to start and stop their servers, never their auth_state, so that nothing mints the token of a user, which the hub keeps for ever.  A TRE that lists none, eg a production
 * one, has nothing of the smoke tests in its hub.
 */
class JupyterHubSmokeServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] STACKS = {"aws-full", "azure-full", "gcp-full"};

    private static JsonNode plan(String stack, Path dir, Map<String, String> overrides) throws Exception {
        Map<String, String> config = new java.util.HashMap<>(Map.of("GOOGLE_APPLICATION_CREDENTIALS", "{\"a\":\"b\"}"));
        config.putAll(overrides);
        return JSON.readTree(SynthesisSupport.synthesise(StackId.parse(stack), dir.resolve(stack), config));
    }

    private static String hubConfig(JsonNode plan) {
        return plan.path("resource").path("kubernetes_config_map").path("jupyterhubConfigMap").path("data").path("jupyterhub_config.py").asText();
    }

    @Test
    void aTreWithSmokeMembersHasTheServiceWithItsTokenFromTheSecretAndARoleOfOnlyWhatTheyNeed(@TempDir Path dir) throws Exception {
        for (String stack : STACKS) {
            JsonNode plan = plan(stack, dir, Map.of("SMOKE_MEMBER", "smoke@example.org"));

            assertThat(hubConfig(plan)).as(stack).contains("c.JupyterHub.services = [{'name': 'afon-smoke', 'api_token': os.environ['AFON_SMOKE_API_TOKEN']}]")
                              .contains("c.JupyterHub.load_roles = [{'name': 'afon-smoke', 'scopes': ['servers', 'read:users'], 'services': ['afon-smoke']}]")
                              .doesNotContain("admin:users").doesNotContain("admin:auth_state");
            assertThat(plan.path("resource").path("kubernetes_secret").path("jupyterhubSecret").path("data").has("smoke.apiToken")).as(stack).isTrue();
            assertThat(plan.path("resource").path("kubernetes_deployment").toString()).as(stack).contains("AFON_SMOKE_API_TOKEN");
        }
    }

    @Test
    void aTreWithNoSmokeMembersHasNothingOfTheSmokeTestsInItsHub(@TempDir Path dir) throws Exception {
        for (String stack : STACKS) {
            JsonNode plan = plan(stack, dir, Map.of());

            assertThat(hubConfig(plan)).as(stack).doesNotContain("afon-smoke").doesNotContain("AFON_SMOKE_API_TOKEN").doesNotContain("load_roles");
            assertThat(plan.path("resource").path("kubernetes_secret").path("jupyterhubSecret").path("data").has("smoke.apiToken")).as(stack).isFalse();
            assertThat(plan.path("resource").path("kubernetes_deployment").toString()).as(stack).doesNotContain("AFON_SMOKE_API_TOKEN");
        }
    }
}
