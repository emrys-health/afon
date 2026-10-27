package health.emrys.afon.cdk.azure;

import com.hashicorp.cdktf.TerraformResourceLifecycle;
import com.hashicorp.cdktf.providers.azurerm.data_azurerm_resources.DataAzurermResources;
import com.hashicorp.cdktf.providers.azurerm.managed_disk.ManagedDisk;
import com.hashicorp.cdktf.providers.azurerm.role_assignment.RoleAssignment;
import com.hashicorp.cdktf.providers.azurerm.role_assignment.RoleAssignmentConfig;
import com.hashicorp.cdktf.providers.azurerm.user_assigned_identity.UserAssignedIdentity;
import health.emrys.afon.cdk.RetainedVolume;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

/**
 * A managed disk that outlives the cluster, see {@link RetainedVolume}, named by its name.
 * <p>
 * The disk is in the resource group of the stack, not in the one that AKS creates for the cluster, which goes with it.  The cluster's
 * identity is given the {@code Contributor} role on the disk, as it is outside the resource group the cluster manages, which it needs
 * to attach it to a node; the role assignment goes with the stack, and is made again on the next deploy.  The nodes are not zonal,
 * as with the volumes the default StorageClass provisions, so neither is the disk.
 */
class AzureRetainedVolume extends RetainedVolume {

    /** As the default StorageClass of AKS, so that the disk is as it would have been. */
    static final String DISK_SKU = "StandardSSD_LRS";

    private final String               resourceGroup;
    private final String               location;
    private final UserAssignedIdentity identity;

    /**
     * @param volumeName    the name of the disk, eg {@code gitea-full-emrys-cluster}
     * @param resourceGroup the resource group of the disk, ie that of the stack
     * @param location      the location of the disk, that of the resource group
     * @param identity      the identity of the cluster, which attaches the disk
     */
    AzureRetainedVolume(String name, String volumeName, String size, String namespace, String resourceGroup, String location,
                        UserAssignedIdentity identity) {
        super(name, volumeName, size, namespace);
        this.resourceGroup = resourceGroup;
        this.location      = location;
        this.identity      = identity;
    }

    @Override
    protected Volume createVolume(Construct scope, List<Map<String, String>> imports) {
        // Read when planning, as the import of what it finds must be known then: it must depend on nothing.
        DataAzurermResources lookup = DataAzurermResources.Builder
            .create(scope, name + "RetainedVolumeLookup")
            .name(volumeName)
            .resourceGroupName(resourceGroup)
            .type("Microsoft.Compute/disks")
            .build();
        lookup.overrideLogicalId(logicalId() + "_lookup");

        ManagedDisk disk = ManagedDisk.Builder
            .create(scope, name + "RetainedVolume")
            .name(volumeName)
            .location(location)
            .resourceGroupName(resourceGroup)
            .storageAccountType(DISK_SKU)
            .createOption("Empty")
            .diskSizeGb(gib)
            .tags(Map.of("Name", volumeName))
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(true).build())
            .build();
        disk.overrideLogicalId(logicalId());
        importIfExists(imports, "azurerm_managed_disk", "data.azurerm_resources." + logicalId() + "_lookup.resources[*].id");

        // The disk is outside the resource group that the cluster's identity manages, so it needs a role on it to attach it.
        RoleAssignment attach = new RoleAssignment(
            scope, name + "RetainedVolumeAttach", RoleAssignmentConfig
            .builder()
            .principalId(identity.getPrincipalId())
            .roleDefinitionName("Contributor")
            .scope(disk.getId())
            .build());
        return new Volume(disk.getId(), List.of(attach));
    }

    @Override
    protected String csiDriver() {
        return "disk.csi.azure.com";
    }
}
