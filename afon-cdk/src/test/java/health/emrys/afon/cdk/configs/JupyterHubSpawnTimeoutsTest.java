package health.emrys.afon.cdk.configs;

import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.cdk.SynthesisSupport;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Creating a pod is answered only after the admission webhooks have answered, and Kyverno's image checks go to the registries, so can
 * take longer than the 3 seconds that KubeSpawner gives each call by default, when the registry limits the pulls.
 */
class JupyterHubSpawnTimeoutsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String hubConfigOf(String stack, Path dir) throws Exception {
        // which the GCP stack needs for the monitoring
        String plan = SynthesisSupport.synthesise(StackId.parse(stack), dir, Map.of("GOOGLE_APPLICATION_CREDENTIALS", "{\"a\":\"b\"}"));
        return JSON.readTree(plan).path("resource").path("kubernetes_config_map").path("jupyterhubConfigMap")
                   .path("data").path("jupyterhub_config.py").asText();
    }

    @Test
    void theHubGivesTheCreatePodCallMoreThanTheDefaultOfThreeSeconds(@TempDir Path dir) throws Exception {
        for (String stack : new String[]{"aws-full", "azure-full", "gcp-full"}) {
            String config = hubConfigOf(stack, dir.resolve(stack));

            assertThat(config).as(stack).contains("c.KubeSpawner.k8s_api_request_timeout = 30\n")
                              .contains("c.KubeSpawner.k8s_api_request_retry_timeout = 120\n");
        }
    }
}
