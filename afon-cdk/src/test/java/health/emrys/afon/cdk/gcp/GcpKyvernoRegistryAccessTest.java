package health.emrys.afon.cdk.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.cdk.SynthesisSupport;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With {@code GCP_WORKLOAD_IDENTITY}, Kyverno reads the signatures of the images from Artifact Registry as a service account of its own, which has no
 * access to the Cloud KMS key; without it, as the service account of the node.
 */
class GcpKyvernoRegistryAccessTest {

    private static final ObjectMapper JSON     = new ObjectMapper();
    private static final String       REGISTRY = "europe-west2-docker.pkg.dev/my-project/afon/emrys";
    private static final Map<String, String> ON = Map.of("GCP_IMAGE_REGISTRY", REGISTRY, "GCP_WORKLOAD_IDENTITY", "true");

    private static JsonNode plan(Path dir, Map<String, String> overrides) throws Exception {
        Map<String, String> config = new HashMap<>(overrides);
        // which the monitoring of gcp-full needs, for Loki to write its logs
        config.put("GOOGLE_APPLICATION_CREDENTIALS", "{\"type\":\"service_account\"}");
        return JSON.readTree(SynthesisSupport.synthesise(StackId.parse("gcp-full"), dir, config));
    }

    private static JsonNode resource(JsonNode plan, String type, String idPart) {
        for (var entry : plan.path("resource").path(type).properties()) {
            if (entry.getKey().contains(idPart)) {
                return entry.getValue();
            }
        }
        return JSON.missingNode();
    }

    @Test
    void recognisesAnArtifactRegistry() {
        assertThat(GcpCloudProvider.artifactRegistry(REGISTRY)).contains(List.of("europe-west2", "my-project", "afon"));
        assertThat(GcpCloudProvider.artifactRegistry("europe-west2-docker.pkg.dev/my-project/afon")).isPresent();
        assertThat(GcpCloudProvider.artifactRegistry("europe-west2-docker.pkg.dev/my-project")).isEmpty();
        assertThat(GcpCloudProvider.artifactRegistry("123456789012.dkr.ecr.eu-west-2.amazonaws.com/emrys")).isEmpty();
        assertThat(GcpCloudProvider.artifactRegistry("")).isEmpty();
    }

    @Test
    void theServiceAccountIdFitsWhatGoogleAllows() {
        assertThat(GcpCloudProvider.registryServiceAccountId("full-emrys2-cluster")).isEqualTo("kyverno-full-emrys2-cluster");
        String longId = GcpCloudProvider.registryServiceAccountId("full-a-very-long-cluster-name-indeed");
        assertThat(longId).hasSizeLessThanOrEqualTo(30).matches("[a-z][a-z0-9-]*[a-z0-9]");
        // so that clusters whose names differ late do not get the same one
        assertThat(GcpCloudProvider.registryServiceAccountId("full-a-very-long-cluster-name-indeed-one"))
            .isNotEqualTo(GcpCloudProvider.registryServiceAccountId("full-a-very-long-cluster-name-indeed-two"));
    }

    @Test
    void theServiceAccountsOfKyvernoCanReadTheRepositoryOnlyAndAreAnnotatedOnlyWithWorkloadIdentityAndAnArtifactRegistry(
        @TempDir Path dir, @TempDir Path other, @TempDir Path noIdentity, @TempDir Path ecr) throws Exception {
        JsonNode with = plan(dir, ON);

        JsonNode reader = resource(with, "google_artifact_registry_repository_iam_member", "kyvernoRegistryReader");
        assertThat(reader.path("role").asText()).isEqualTo("roles/artifactregistry.reader");
        assertThat(reader.path("repository").asText()).isEqualTo("afon");
        assertThat(reader.path("project").asText()).isEqualTo("my-project");
        assertThat(with.path("resource").has("google_kms_crypto_key_iam_member")).isFalse();

        // any of the three service accounts that verify images may act as the Google one
        for (String account : List.of("kyverno-admission-controller", "kyverno-background-controller", "kyverno-reports-controller")) {
            JsonNode binding = resource(with, "google_service_account_iam_member", "kyvernoWorkloadIdentity-" + account);
            assertThat(binding.path("role").asText()).isEqualTo("roles/iam.workloadIdentityUser");
            assertThat(binding.path("member").asText()).endsWith(".svc.id.goog[kyverno/" + account + "]");
        }
        String release = with.path("resource").path("helm_release").toString();
        assertThat(release).contains("admissionController.rbac.serviceAccount.annotations.iam\\\\.gke\\\\.io/gcp-service-account",
                                     "backgroundController.rbac.serviceAccount.annotations", "reportsController.rbac.serviceAccount.annotations");

        // a registry alone does not give Kyverno a service account of its own, nor a registry that is not an Artifact Registry, with the flag
        for (JsonNode without : List.of(plan(other, Map.of()),
                                        plan(noIdentity, Map.of("GCP_IMAGE_REGISTRY", REGISTRY)),
                                        plan(ecr, Map.of("GCP_IMAGE_REGISTRY", "123456789012.dkr.ecr.eu-west-2.amazonaws.com/emrys",
                                                         "GCP_WORKLOAD_IDENTITY", "true")))) {
            assertThat(resource(without, "google_service_account", "kyvernoRegistryServiceAccount").isMissingNode()).isTrue();
            assertThat(without.path("resource").path("helm_release").toString()).doesNotContain("iam\\\\.gke\\\\.io/gcp-service-account");
        }
    }

    @Test
    void theClusterHasWorkloadIdentityOnlyIfSet(@TempDir Path dir, @TempDir Path other, @TempDir Path registryOnly) throws Exception {
        // with no registry too: the flag is not inferred from it, nor the registry from the flag
        JsonNode with = resource(plan(dir, Map.of("GCP_WORKLOAD_IDENTITY", "true")), "google_container_cluster", "gkeCluster");
        JsonNode config = with.path("workload_identity_config");
        assertThat((config.isArray() ? config.get(0) : config).path("workload_pool").asText()).endsWith(".svc.id.goog");
        JsonNode nodes = with.path("node_config");
        assertThat((nodes.isArray() ? nodes.get(0) : nodes).path("workload_metadata_config").toString()).contains("GKE_METADATA");

        for (Path dirWithout : List.of(other, registryOnly)) {
            JsonNode without = resource(plan(dirWithout, dirWithout == other ? Map.of() : Map.of("GCP_IMAGE_REGISTRY", REGISTRY)),
                                        "google_container_cluster", "gkeCluster");
            assertThat(without.has("workload_identity_config")).isFalse();
        }
    }
}
