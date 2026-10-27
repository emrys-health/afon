package health.emrys.afon.cdk.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.cdk.SynthesisSupport;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AwsRetainedVolumeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode plan(String stack, Path dir) throws Exception {
        return JSON.readTree(SynthesisSupport.synthesise(StackId.parse(stack), dir, Map.of()));
    }

    /** The resource whose name contains the part: synthesised names carry the scope and a hash of the construct. */
    private static JsonNode named(JsonNode resources, String part) {
        for (var entry : resources.properties()) {
            if (entry.getKey().contains(part)) {
                return entry.getValue();
            }
        }
        return JSON.missingNode();
    }

    /** A block, which is a list of one when it is declared as such. */
    private static JsonNode block(JsonNode node) {
        return node.isArray() ? node.get(0) : node;
    }

    @Test
    void sizesVolumesInWholeGib() {
        assertThat(AwsRetainedVolume.gib("10Gi")).isEqualTo(10);
        assertThat(AwsRetainedVolume.gib(" 200Gi ")).isEqualTo(200);
    }

    @Test
    void rejectsSizesThatAreNotWholeGib() {
        for (String size : new String[]{"10G", "512Mi", "1.5Gi", "10"}) {
            assertThatThrownBy(() -> AwsRetainedVolume.gib(size))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(size);
        }
    }

    @Test
    void namesTheResourcesSoThatTheImportCanAddressThem() {
        assertThat(AwsRetainedVolume.logicalId("gitea")).isEqualTo("retained_gitea_volume");
    }

    @Test
    void theVolumeIsKeptWithTheStackAndNamedAfterTheCluster(@TempDir Path dir) throws Exception {
        JsonNode volume = plan("aws-full", dir).path("resource").path("aws_ebs_volume").path("retained_gitea_volume");

        assertThat(volume.isMissingNode()).isFalse();
        assertThat(volume.path("tags").path("Name").asText()).startsWith("gitea-full-");
        assertThat(volume.path("type").asText()).isEqualTo("gp3");
        assertThat(volume.path("encrypted").asBoolean()).isTrue();
        assertThat(volume.path("size").asInt()).isEqualTo(10);
        // zonal, in the zone of the first subnets, which has nodes
        assertThat(volume.path("availability_zone").asText()).endsWith("a");
        // the stack's destroy excludes what has this, so the volume is kept; a plain tofu destroy fails instead of deleting it
        assertThat(volume.path("lifecycle").path("prevent_destroy").asBoolean()).isTrue();
    }

    @Test
    void aVolumeThatExistsIsImportedByItsNameBeforeItIsCreated(@TempDir Path dir) throws Exception {
        JsonNode plan = plan("aws-full", dir);

        JsonNode lookup = plan.path("data").path("aws_ebs_volumes").path("retained_gitea_volume_lookup");
        assertThat(lookup.toString()).contains("tag:Name").contains("gitea-full-");
        JsonNode importBlock = plan.path("import").get(0);
        assertThat(importBlock.path("to").asText()).isEqualTo("aws_ebs_volume.retained_gitea_volume");
        assertThat(importBlock.path("for_each").asText()).contains("data.aws_ebs_volumes.retained_gitea_volume_lookup.ids");
        assertThat(importBlock.path("id").asText()).isEqualTo("${each.value}");
    }

    @Test
    void thePersistentVolumeRetainsTheVolumeAndIsBoundOnlyToItsClaim(@TempDir Path dir) throws Exception {
        JsonNode resources = plan("aws-full", dir).path("resource");

        JsonNode spec = block(resources.path("kubernetes_persistent_volume_v1").elements().next().path("spec"));
        assertThat(spec.path("persistent_volume_reclaim_policy").asText()).isEqualTo("Retain");
        assertThat(spec.path("storage_class_name").asText()).isEqualTo(AwsRetainedVolume.STORAGE_CLASS);
        JsonNode csi = block(block(spec.path("persistent_volume_source")).path("csi"));
        assertThat(csi.path("driver").asText()).isEqualTo("ebs.csi.aws.com");
        assertThat(csi.path("volume_handle").asText()).contains("aws_ebs_volume.retained_gitea_volume");
        JsonNode claim = named(resources.path("kubernetes_persistent_volume_claim_v1"), "giteaRetainedVolumeClaim");
        assertThat(claim.isMissingNode()).isFalse();
        assertThat(block(claim.path("spec")).path("storage_class_name").asText()).isEqualTo(AwsRetainedVolume.STORAGE_CLASS);
    }

    @Test
    void thePodsAreScheduledToTheZoneOfTheVolume(@TempDir Path dir) throws Exception {
        JsonNode spec = block(plan("aws-full", dir).path("resource").path("kubernetes_persistent_volume_v1")
                                                   .elements().next().path("spec"));

        String affinity = spec.path("node_affinity").toString();
        assertThat(affinity).contains("topology.kubernetes.io/zone").contains("\"In\"");
    }

    @Test
    void theVolumeIsSnapshottedDailyAndTheLastSevenKept(@TempDir Path dir) throws Exception {
        JsonNode policy = named(plan("aws-full", dir).path("resource").path("aws_dlm_lifecycle_policy"), "giteaRetainedVolumeSnapshots");

        assertThat(policy.isMissingNode()).isFalse();
        JsonNode details = block(policy.path("policy_details"));
        assertThat(details.path("target_tags").path("Name").asText()).startsWith("gitea-full-");
        JsonNode schedule = block(details.path("schedule"));
        assertThat(block(schedule.path("create_rule")).path("interval").asInt()).isEqualTo(24);
        assertThat(block(schedule.path("create_rule")).path("times").toString()).contains("03:00");
        assertThat(block(schedule.path("retain_rule")).path("count").asInt()).isEqualTo(AwsRetainedVolume.SNAPSHOTS_KEPT);
    }

    @Test
    void giteaKeepsItsRepositoriesOnTheRetainedClaimInsteadOfTheChartsOwn(@TempDir Path dir) throws Exception {
        JsonNode release = plan("aws-full", dir).path("resource").path("helm_release").path("gitea");

        assertThat(release.toString()).contains("gitea-retained");
    }

    @Test
    void aStackWithoutGiteaHasNoVolume(@TempDir Path dir) throws Exception {
        JsonNode plan = plan("aws-keycloak", dir);

        assertThat(plan.path("resource").has("aws_ebs_volume")).isFalse();
        assertThat(plan.has("import")).isFalse();
    }
}
