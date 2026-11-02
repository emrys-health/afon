package health.emrys.afon.cdk.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.cdk.RetainedVolume;
import health.emrys.afon.cdk.SynthesisSupport;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The repositories of Gitea are kept on a Persistent Disk that {@code gcp-create} creates, so that it outlives the stack, which only
 * looks it up.
 */
class GcpRetainedVolumeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode plan(String stack, Path dir) throws Exception {
        // which the monitoring of gcp-full needs
        return JSON.readTree(SynthesisSupport.synthesise(StackId.parse(stack), dir,
                                                         Map.of("GOOGLE_APPLICATION_CREDENTIALS", "{\"type\":\"service_account\"}")));
    }

    /** A block, which is a list of one when it is declared as such. */
    private static JsonNode block(JsonNode node) {
        return node.isArray() ? node.get(0) : node;
    }

    @Test
    void theDiskIsLookedUpByItsNameInTheZoneOfTheCluster(@TempDir Path dir) throws Exception {
        JsonNode plan = plan("gcp-full", dir);

        JsonNode lookup = plan.path("data").path("google_compute_disk").elements().next();
        assertThat(lookup.path("name").asText()).startsWith("gitea-full-");
        assertThat(lookup.path("zone").asText()).isEqualTo("europe-west2-a");
        assertThat(lookup.path("project").asText()).isNotBlank();
        // the stack does not create it, as that of a stack would go with it, as it is created by gcp-create
        assertThat(plan.path("resource").has("google_compute_disk")).isFalse();
        assertThat(plan.has("import")).isFalse();
    }

    @Test
    void thePersistentVolumeRetainsTheDiskAndIsBoundOnlyToItsClaim(@TempDir Path dir) throws Exception {
        JsonNode resources = plan("gcp-full", dir).path("resource");

        JsonNode spec = block(resources.path("kubernetes_persistent_volume_v1").elements().next().path("spec"));
        assertThat(spec.path("persistent_volume_reclaim_policy").asText()).isEqualTo("Retain");
        assertThat(spec.path("storage_class_name").asText()).isEqualTo(RetainedVolume.STORAGE_CLASS);
        JsonNode csi = block(block(spec.path("persistent_volume_source")).path("csi"));
        assertThat(csi.path("driver").asText()).isEqualTo("pd.csi.storage.gke.io");
        assertThat(csi.path("volume_handle").asText()).matches("projects/.+/zones/europe-west2-a/disks/gitea-full-.+");
        assertThat(resources.path("kubernetes_persistent_volume_claim_v1").toString()).contains("gitea-retained");
    }

    @Test
    void theDiskIsZonalSoThePodsAreScheduledToItsZone(@TempDir Path dir) throws Exception {
        JsonNode spec = block(plan("gcp-full", dir).path("resource").path("kubernetes_persistent_volume_v1").elements().next().path("spec"));

        assertThat(spec.path("node_affinity").toString()).contains("topology.kubernetes.io/zone").contains("europe-west2-a");
    }

    @Test
    void giteaKeepsItsRepositoriesOnTheRetainedClaimInsteadOfTheChartsOwn(@TempDir Path dir) throws Exception {
        assertThat(plan("gcp-full", dir).path("resource").path("helm_release").path("gitea").toString()).contains("gitea-retained");
    }

    @Test
    void aStackWithoutGiteaHasNoDisk(@TempDir Path dir) throws Exception {
        JsonNode plan = plan("gcp-keycloak", dir);

        assertThat(plan.path("data").has("google_compute_disk")).isFalse();
        assertThat(plan.path("resource").has("kubernetes_persistent_volume_v1")).isFalse();
    }
}
