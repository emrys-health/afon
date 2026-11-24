package health.emrys.afon.cdk.aws;

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
 * The ACM certificate and the Route 53 zone of each domain name of the ALBs are looked up from it when planning, rather than
 * configured, so that they cannot drift from what is in AWS.
 */
class AwsHttpsLookupTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode plan(String stack, Path dir) throws Exception {
        return JSON.readTree(SynthesisSupport.synthesise(StackId.parse(stack), dir));
    }

    private static List<JsonNode> dataSources(JsonNode plan, String type) {
        List<JsonNode> found = new ArrayList<>();
        plan.path("data").path(type).elements().forEachRemaining(found::add);
        return found;
    }

    @Test
    void eachDomainNameHasTheLatestIssuedCertificateAndTheZoneNamedAfterIt(@TempDir Path dir) throws Exception {
        JsonNode plan = plan("aws-full", dir);

        assertThat(dataSources(plan, "aws_acm_certificate")).extracting(node -> node.path("domain").asText())
            .containsExactlyInAnyOrder("emrysoauth.click", "polaris.golden.example", "jupyter.golden.example", "auth.jupyter.golden.example");
        assertThat(dataSources(plan, "aws_acm_certificate")).allSatisfy(node -> {
            assertThat(node.path("most_recent").asBoolean()).isTrue();
            assertThat(node.path("statuses").get(0).asText()).isEqualTo("ISSUED");
        });
        assertThat(dataSources(plan, "aws_route53_zone")).extracting(node -> node.path("name").asText())
            .containsExactlyInAnyOrder("emrysoauth.click", "polaris.golden.example", "jupyter.golden.example", "jupyter.golden.example");
    }

    @Test
    void theIngressesUseTheLookedUpCertificateAndTheAliasRecordsTheLookedUpZone(@TempDir Path dir) throws Exception {
        String plan = plan("aws-keycloak", dir).toString();

        assertThat(plan).contains("alb.ingress.kubernetes.io/certificate-arn")
                        .contains("data.aws_acm_certificate.").contains("data.aws_route53_zone.");
    }

    @Test
    void jupyterAndAuthIngressesShareAlbGroupAndOneHourIdleTimeout(@TempDir Path dir) throws Exception {
        JsonNode plan = plan("aws-full", dir);
        List<JsonNode> jupyterIngresses = new ArrayList<>();
        plan.path("resource").path("kubernetes_ingress_v1").elements().forEachRemaining(ingress -> {
            String name = ingress.path("metadata").path("name").asText();
            if ("jupyter-ingress".equals(name) || "jupyter-auth-ingress".equals(name)) {
                jupyterIngresses.add(ingress);
            }
        });
        assertThat(jupyterIngresses).hasSize(2);
        assertThat(jupyterIngresses).allSatisfy(ingress -> {
            JsonNode annotations = ingress.path("metadata").path("annotations");
            assertThat(annotations.path(AwsCloudProvider.ALB_GROUP_NAME_ANNOTATION).asText()).isEqualTo("jupyter");
            assertThat(annotations.path(AwsCloudProvider.ALB_LB_ATTRIBUTES_ANNOTATION).asText())
                .isEqualTo("idle_timeout.timeout_seconds=3600");
        });

        JsonNode guacPolicy = plan.path("resource").path("kubernetes_network_policy").path("guacamoleIngressPolicy");
        assertThat(guacPolicy.path("metadata").path("name").asText()).isEqualTo("guacamole-only-from-gateway");
        JsonNode rule = guacPolicy.path("spec").path("ingress").get(0);
        assertThat(rule.path("from").get(0).path("pod_selector").path("match_labels").path("app").asText())
            .isEqualTo("pomerium");
        assertThat(rule.path("ports").get(0).path("port").asText()).isEqualTo("8080");
    }
}
