package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.TerraformResourceLifecycle;
import com.hashicorp.cdktf.providers.aws.data_aws_ebs_volumes.DataAwsEbsVolumes;
import com.hashicorp.cdktf.providers.aws.data_aws_ebs_volumes.DataAwsEbsVolumesFilter;
import com.hashicorp.cdktf.providers.aws.dlm_lifecycle_policy.DlmLifecyclePolicy;
import com.hashicorp.cdktf.providers.aws.dlm_lifecycle_policy.DlmLifecyclePolicyPolicyDetails;
import com.hashicorp.cdktf.providers.aws.dlm_lifecycle_policy.DlmLifecyclePolicyPolicyDetailsSchedule;
import com.hashicorp.cdktf.providers.aws.dlm_lifecycle_policy.DlmLifecyclePolicyPolicyDetailsScheduleCreateRule;
import com.hashicorp.cdktf.providers.aws.dlm_lifecycle_policy.DlmLifecyclePolicyPolicyDetailsScheduleRetainRule;
import com.hashicorp.cdktf.providers.aws.ebs_volume.EbsVolume;
import com.hashicorp.cdktf.providers.aws.iam_role.IamRole;
import health.emrys.afon.cdk.RetainedVolume;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An EBS volume that outlives the cluster, see {@link RetainedVolume}, named by its {@code Name} tag.  EBS volumes are zonal, so the
 * volume is in the zone of the first subnets, and the PersistentVolume's pods are scheduled to nodes there.
 * <p>
 * While the cluster runs, Data Lifecycle Manager snapshots the volume daily, keeping the last {@link #SNAPSHOTS_KEPT}.
 * The policy goes with the cluster, as the volume only changes while the cluster runs, but its snapshots are kept.
 */
class AwsRetainedVolume extends RetainedVolume {

    static final int SNAPSHOTS_KEPT = 7;

    private final String  zone;
    private final IamRole snapshotRole;

    /**
     * @param volumeName   the {@code Name} tag of the volume, eg {@code gitea-full-emrys-cluster}
     * @param zone         the zone of the volume, which must have nodes
     * @param snapshotRole the role Data Lifecycle Manager snapshots the volume as
     */
    AwsRetainedVolume(String name, String volumeName, String size, String namespace, String zone, IamRole snapshotRole) {
        super(name, volumeName, size, namespace);
        this.zone         = zone;
        this.snapshotRole = snapshotRole;
    }

    @Override
    protected Volume createVolume(Construct scope, List<Map<String, String>> imports) {
        // Read when planning, as the import of what it finds must be known then: it must depend on nothing.
        DataAwsEbsVolumes lookup = DataAwsEbsVolumes.Builder
            .create(scope, name + "RetainedVolumeLookup")
            .filter(List.of(DataAwsEbsVolumesFilter.builder().name("tag:Name").values(List.of(volumeName)).build()))
            .build();
        lookup.overrideLogicalId(logicalId() + "_lookup");

        EbsVolume volume = EbsVolume.Builder
            .create(scope, name + "RetainedVolume")
            .availabilityZone(zone)
            .size(gib)
            .type("gp3")
            .encrypted(true)
            .tags(Map.of("Name", volumeName))
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(true).build())
            .build();
        volume.overrideLogicalId(logicalId());
        importIfExists(imports, "aws_ebs_volume", "data.aws_ebs_volumes." + logicalId() + "_lookup.ids");

        DlmLifecyclePolicy.Builder
            .create(scope, name + "RetainedVolumeSnapshots")
            .description("Daily snapshots of " + volumeName)
            .executionRoleArn(snapshotRole.getArn())
            .state("ENABLED")
            .policyDetails(DlmLifecyclePolicyPolicyDetails
                               .builder()
                               .resourceTypes(List.of("VOLUME"))
                               .targetTags(Map.of("Name", volumeName))
                               .schedule(List.of(DlmLifecyclePolicyPolicyDetailsSchedule
                                                     .builder()
                                                     .name("daily")
                                                     .createRule(DlmLifecyclePolicyPolicyDetailsScheduleCreateRule
                                                                     .builder()
                                                                     .interval(24)
                                                                     .intervalUnit("HOURS")
                                                                     .times(List.of("03:00"))
                                                                     .build())
                                                     .retainRule(DlmLifecyclePolicyPolicyDetailsScheduleRetainRule
                                                                     .builder()
                                                                     .count(SNAPSHOTS_KEPT)
                                                                     .build())
                                                     .copyTags(true)
                                                     .build()))
                               .build())
            .tags(Map.of("Name", volumeName + "-snapshots"))
            .build();
        return new Volume(volume.getId(), List.of());
    }

    @Override
    protected String csiDriver() {
        return "ebs.csi.aws.com";
    }

    @Override
    protected Optional<String> zone() {
        return Optional.of(zone);
    }
}
