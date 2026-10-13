package health.emrys.afon.cdk.configs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.cdk.SynthesisSupport;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the ingress controller runs in the cluster, as the nginx one does on Azure, the address that a pod gets for what it
 * exposes, eg Polaris, is that of a service of the cluster, which Cilium translates to the controller's pod: the egress policy of
 * the Spark driver, which calls Polaris by its domain name, must allow that pod, as the rule of the domain names, which matches
 * an address outside of the cluster, as the AWS ALB is, does not apply to it.
 */
class NetworkPoliciesIngressTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The manifest of the Cilium policy applied by the null resource of the pod, which is echoed to kubectl. */
    private static String policyOf(String stackId, String pod, Path dir) throws Exception {
        JsonNode plan = JSON.readTree(SynthesisSupport.synthesise(StackId.parse(stackId), dir));
        return plan.path("resource").path("null_resource").path(pod + "-policy").path("provisioner").get(0)
                   .path("local-exec").path("command").asText();
    }

    @Test
    void sparkDriverMayReachTheIngressControllerWhereItRunsInTheCluster(@TempDir Path dir) throws Exception {
        String driver = policyOf("azure-full", "spark-driver", dir);

        assertThat(driver).contains("\"k8s:io.kubernetes.pod.namespace\":\"ingress-nginx\"")
                          .contains("\"k8s:app.kubernetes.io/name\":\"ingress-nginx\"")
                          .contains("\"port\":\"443\"");
    }

    @Test
    void onlyTheSparkDriverDoes(@TempDir Path dir) throws Exception {
        assertThat(policyOf("azure-full", "spark-master", dir)).doesNotContain("ingress-nginx");
    }

    @Test
    void notWhereTheIngressIsOutsideOfTheCluster(@TempDir Path dir) throws Exception {
        assertThat(policyOf("aws-full", "spark-driver", dir)).doesNotContain("ingress-nginx");
        assertThat(policyOf("aws-full", "spark-master", dir)).doesNotContain("ingress-nginx");
    }
}
