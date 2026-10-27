package health.emrys.afon.cdk.azure;

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

class AzureRetainedVolumeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode plan(String stack, Path dir) throws Exception {
        return JSON.readTree(SynthesisSupport.synthesise(StackId.parse(stack), dir, Map.of()));
    }

    /** The resource whose name starts with the prefix: synthesised names carry a hash of the construct. */
    private static JsonNode named(JsonNode resources, String prefix) {
        for (var entry : resources.properties()) {
            if (entry.getKey().startsWith(prefix)) {
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
    void sizesDisksInWholeGib() {
        assertThat(AzureRetainedVolume.gib("10Gi")).isEqualTo(10);
        assertThat(AzureRetainedVolume.gib(" 200Gi ")).isEqualTo(200);
    }

    @Test
    void rejectsSizesThatAreNotWholeGib() {
        for (String size : new String[]{"10G", "512Mi", "1.5Gi", "10"}) {
            assertThatThrownBy(() -> AzureRetainedVolume.gib(size))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(size);
        }
    }

    @Test
    void namesTheResourcesSoThatTheImportCanAddressThem() {
        assertThat(AzureRetainedVolume.logicalId("gitea")).isEqualTo("retained_gitea_volume");
    }

    @Test
    void theDiskIsKeptWithTheStackAndNamedAfterTheClusterInTheResourceGroupOfTheStack(@TempDir Path dir) throws Exception {
        JsonNode disk = plan("azure-full", dir).path("resource").path("azurerm_managed_disk").path("retained_gitea_volume");

        assertThat(disk.isMissingNode()).isFalse();
        assertThat(disk.path("name").asText()).startsWith("gitea-full-");
        // the resource group of the stack, which is kept, not the one that AKS creates for the cluster
        assertThat(disk.path("resource_group_name").asText()).isNotBlank().doesNotContain("MC_");
        assertThat(disk.path("create_option").asText()).isEqualTo("Empty");
        assertThat(disk.path("storage_account_type").asText()).isEqualTo(AzureRetainedVolume.DISK_SKU);
        assertThat(disk.path("disk_size_gb").asInt()).isEqualTo(10);
        // the stack's destroy excludes what has this, so the disk is kept; a plain tofu destroy fails instead of deleting it
        assertThat(disk.path("lifecycle").path("prevent_destroy").asBoolean()).isTrue();
    }

    @Test
    void aDiskThatExistsIsImportedByItsNameBeforeItIsCreated(@TempDir Path dir) throws Exception {
        JsonNode plan = plan("azure-full", dir);

        JsonNode lookup = plan.path("data").path("azurerm_resources").path("retained_gitea_volume_lookup");
        assertThat(lookup.path("name").asText()).startsWith("gitea-full-");
        assertThat(lookup.path("type").asText()).isEqualTo("Microsoft.Compute/disks");
        JsonNode importBlock = plan.path("import").get(0);
        assertThat(importBlock.path("to").asText()).isEqualTo("azurerm_managed_disk.retained_gitea_volume");
        assertThat(importBlock.path("for_each").asText()).contains("data.azurerm_resources.retained_gitea_volume_lookup.resources");
        assertThat(importBlock.path("id").asText()).isEqualTo("${each.value}");
    }

    @Test
    void thePersistentVolumeRetainsTheDiskAndIsBoundOnlyToItsClaim(@TempDir Path dir) throws Exception {
        JsonNode resources = plan("azure-full", dir).path("resource");

        JsonNode spec = block(resources.path("kubernetes_persistent_volume_v1").elements().next().path("spec"));
        assertThat(spec.path("persistent_volume_reclaim_policy").asText()).isEqualTo("Retain");
        assertThat(spec.path("storage_class_name").asText()).isEqualTo(AzureRetainedVolume.STORAGE_CLASS);
        JsonNode csi = block(block(spec.path("persistent_volume_source")).path("csi"));
        assertThat(csi.path("driver").asText()).isEqualTo("disk.csi.azure.com");
        assertThat(csi.path("volume_handle").asText()).contains("azurerm_managed_disk.retained_gitea_volume");
        JsonNode claim = named(resources.path("kubernetes_persistent_volume_claim_v1"), "azure_giteaRetainedVolumeClaim");
        assertThat(claim.isMissingNode()).isFalse();
        assertThat(block(claim.path("spec")).path("storage_class_name").asText()).isEqualTo(AzureRetainedVolume.STORAGE_CLASS);
    }

    @Test
    void theClusterIdentityMayAttachTheDisk(@TempDir Path dir) throws Exception {
        JsonNode assignment = named(plan("azure-full", dir).path("resource").path("azurerm_role_assignment"), "azure_giteaRetainedVolumeAttach");

        assertThat(assignment.path("role_definition_name").asText()).isEqualTo("Contributor");
        assertThat(assignment.path("principal_id").asText()).contains("azurerm_user_assigned_identity.");
        assertThat(assignment.path("scope").asText()).contains("azurerm_managed_disk.retained_gitea_volume");
    }

    @Test
    void giteaKeepsItsRepositoriesOnTheRetainedClaimInsteadOfTheChartsOwn(@TempDir Path dir) throws Exception {
        JsonNode release = plan("azure-full", dir).path("resource").path("helm_release").path("gitea");

        assertThat(release.toString()).contains("gitea-retained");
    }

    @Test
    void aStackWithoutGiteaHasNoDisk(@TempDir Path dir) throws Exception {
        JsonNode plan = plan("azure-keycloak", dir);

        assertThat(plan.path("resource").has("azurerm_managed_disk")).isFalse();
        assertThat(plan.has("import")).isFalse();
    }
}
