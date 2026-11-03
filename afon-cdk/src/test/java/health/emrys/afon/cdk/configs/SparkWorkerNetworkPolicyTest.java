package health.emrys.afon.cdk.configs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.cdk.SynthesisSupport;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The executors, and so the users' code, run in the pods of the Spark workers, which may therefore reach no more than the Spark
 * Connect driver does, besides the driver itself and each other.
 */
class SparkWorkerNetworkPolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode plan(String stack, Path dir) throws Exception {
        // which the monitoring of gcp-full needs, for Loki to write its logs
        Map<String, String> config = Map.of("GOOGLE_APPLICATION_CREDENTIALS", "{\"type\":\"service_account\"}");
        return JSON.readTree(SynthesisSupport.synthesise(StackId.parse(stack), dir, config));
    }

    private static JsonNode provisioners(JsonNode plan, String pod) {
        return plan.path("resource").path("null_resource").path(pod + "-policy").path("provisioner");
    }

    /** The Cilium policy, which the create-time command echoes to kubectl. */
    private static JsonNode ciliumPolicy(JsonNode plan, String pod) throws Exception {
        String command = provisioners(plan, pod).get(0).path("local-exec").path("command").asText();
        return JSON.readTree(command.substring(command.indexOf('\'') + 1, command.lastIndexOf('\'')));
    }

    private static String destroyCommand(JsonNode plan, String pod) {
        for (JsonNode provisioner : provisioners(plan, pod)) {
            if (provisioner.path("local-exec").path("when").asText().equals("destroy")) {
                return provisioner.path("local-exec").path("command").asText();
            }
        }
        throw new AssertionError("No destroy-time command for " + pod);
    }

    private static List<JsonNode> egressWith(JsonNode policy, String field) {
        List<JsonNode> rules = new ArrayList<>();
        policy.path("spec").path("egress").forEach(rule -> {
            if (rule.has(field)) {
                rules.add(rule);
            }
        });
        return rules;
    }

    private static List<JsonNode> endpoints(JsonNode policy) {
        List<JsonNode> endpoints = new ArrayList<>();
        egressWith(policy, "toEndpoints").forEach(rule -> rule.path("toEndpoints").forEach(endpoints::add));
        return endpoints;
    }

    @Test
    void theWorkersAreLockedDownToTheDomainsAndTheDnsOfTheDriver(@TempDir Path dir) throws Exception {
        JsonNode plan   = plan("aws-full", dir);
        JsonNode worker = ciliumPolicy(plan, "spark-worker");
        JsonNode driver = ciliumPolicy(plan, "spark-driver");

        assertThat(worker.path("spec").path("endpointSelector").path("matchLabels").path("spark-role").asText()).isEqualTo("worker");
        assertThat(egressWith(worker, "toFQDNs")).isEqualTo(egressWith(driver, "toFQDNs"));
        assertThat(worker.path("spec").path("egress").get(0)).as("the filtered DNS")
                                                            .isEqualTo(driver.path("spec").path("egress").get(0));
    }

    @Test
    void theWorkersReachTheMasterTheDriverAndEachOtherAndNoOtherPod(@TempDir Path dir) throws Exception {
        JsonNode worker = ciliumPolicy(plan("aws-full", dir), "spark-worker");

        List<String> peers = endpoints(worker).stream()
                                              .map(endpoint -> endpoint.path("matchLabels"))
                                              .filter(labels -> labels.has("spark-role"))
                                              .map(labels -> labels.path("spark-role").asText())
                                              .toList();
        assertThat(peers).containsExactlyInAnyOrder("master", "driver", "worker");
        assertThat(endpoints(worker)).allMatch(endpoint -> endpoint.path("matchLabels").has("spark-role")
                                                           || endpoint.path("matchLabels").path("k8s:k8s-app").asText().equals("kube-dns"));
    }

    @Test
    void theDriverReachesTheWorkersForResultsTooLargeToComeBackWithTheirTask(@TempDir Path dir) throws Exception {
        JsonNode driver = ciliumPolicy(plan("aws-full", dir), "spark-driver");

        List<String> peers = endpoints(driver).stream()
                                              .map(endpoint -> endpoint.path("matchLabels"))
                                              .filter(labels -> labels.has("spark-role"))
                                              .map(labels -> labels.path("spark-role").asText())
                                              .toList();
        assertThat(peers).containsExactlyInAnyOrder("master", "worker");
    }

    @Test
    void theMasterIsNotWidened(@TempDir Path dir) throws Exception {
        assertThat(endpoints(ciliumPolicy(plan("aws-full", dir), "spark-master")).toString()).doesNotContain("\"worker\"");
    }

    @Test
    void onGkeTheDriverReachesTheWorkersToo(@TempDir Path dir) throws Exception {
        JsonNode plan   = plan("gcp-full", dir);
        JsonNode driver = null;
        for (var entry : plan.path("resource").path("kubernetes_network_policy").properties()) {
            JsonNode metadata = entry.getValue().path("metadata");
            if ((metadata.isArray() ? metadata.get(0) : metadata).path("name").asText().equals("allow-only-spark-driver-egress")) {
                driver = entry.getValue();
            }
        }

        assertThat(driver).isNotNull();
        assertThat(driver.toString()).contains("\"spark-role\":\"worker\"");
    }

    @Test
    void eachSparkPolicyDeletesOnlyItself(@TempDir Path dir) throws Exception {
        JsonNode plan = plan("aws-full", dir);

        for (String pod : List.of("spark-master", "spark-driver", "spark-worker")) {
            assertThat(destroyCommand(plan, pod)).startsWith("kubectl delete ciliumnetworkpolicy " + pod + "-manifest -n default ");
        }
    }

    @Test
    void onGkeTheWorkersAreLockedDownToo(@TempDir Path dir) throws Exception {
        JsonNode plan   = plan("gcp-full", dir);
        JsonNode worker = null;
        for (var entry : plan.path("resource").path("kubernetes_network_policy").properties()) {
            JsonNode metadata = entry.getValue().path("metadata");
            if ((metadata.isArray() ? metadata.get(0) : metadata).path("name").asText().equals("allow-only-spark-worker-egress")) {
                worker = entry.getValue();
            }
        }

        assertThat(worker).isNotNull();
        assertThat(worker.toString()).contains("\"spark-role\":\"worker\"")
                                     .contains("\"spark-role\":\"master\"")
                                     .contains("\"spark-role\":\"driver\"")
                                     .contains("kube-dns");
        assertThat(plan.path("resource").path("null_resource").has("spark-worker-policy")).as("the FQDN policies").isTrue();
    }
}
