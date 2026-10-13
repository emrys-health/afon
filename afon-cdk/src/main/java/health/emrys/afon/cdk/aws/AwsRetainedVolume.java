package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.ITerraformDependable;
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
import com.hashicorp.cdktf.providers.kubernetes.persistent_volume_claim_v1.PersistentVolumeClaimV1;
import com.hashicorp.cdktf.providers.kubernetes.persistent_volume_claim_v1.PersistentVolumeClaimV1Metadata;
import com.hashicorp.cdktf.providers.kubernetes.persistent_volume_claim_v1.PersistentVolumeClaimV1Spec;
import com.hashicorp.cdktf.providers.kubernetes.persistent_volume_claim_v1.PersistentVolumeClaimV1SpecResources;
import com.hashicorp.cdktf.providers.kubernetes.persistent_volume_v1.PersistentVolumeV1;
import com.hashicorp.cdktf.providers.kubernetes.persistent_volume_v1.PersistentVolumeV1Metadata;
import com.hashicorp.cdktf.providers.kubernetes.persistent_volume_v1.PersistentVolumeV1Spec;
import com.hashicorp.cdktf.providers.kubernetes.persistent_volume_v1.PersistentVolumeV1SpecNodeAffinity;
import com.hashicorp.cdktf.providers.kubernetes.persistent_volume_v1.PersistentVolumeV1SpecNodeAffinityRequired;
import com.hashicorp.cdktf.providers.kubernetes.persistent_volume_v1.PersistentVolumeV1SpecNodeAffinityRequiredNodeSelectorTerm;
import com.hashicorp.cdktf.providers.kubernetes.persistent_volume_v1.PersistentVolumeV1SpecNodeAffinityRequiredNodeSelectorTermMatchExpressions;
import com.hashicorp.cdktf.providers.kubernetes.persistent_volume_v1.PersistentVolumeV1SpecPersistentVolumeSource;
import com.hashicorp.cdktf.providers.kubernetes.persistent_volume_v1.PersistentVolumeV1SpecPersistentVolumeSourceCsi;
import health.emrys.afon.cdk.RetainedVolumeClaim;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.String.format;

/**
 * An EBS volume that outlives the cluster, bound statically to a claim, see
 * {@link health.emrys.afon.cdk.CloudPlatform#retainedVolumeClaim}.
 * <p>
 * The volume is named, by its {@code Name} tag, after what it is for and the cluster, eg
 * {@code gitea-full-emrys-cluster}.  If a volume of that name exists, it is imported into the state, else it is
 * created: afon deletes the state with the stack, so a cluster of the same name finds the volume again by its name.
 * It has {@code prevent_destroy}, which afon's destroy excludes it by, so that it is kept, while a plain
 * {@code tofu destroy} fails rather than deletes it.
 * <p>
 * The PersistentVolume of the cluster points at the volume, with the reclaim policy {@code Retain}, and is bound to
 * the claim by name, of a storage class that no StorageClass is, so that no provisioner ever acts on either.  EBS
 * volumes are zonal, so the volume is in the zone of the first subnets, and the PersistentVolume's pods are scheduled
 * to nodes there.
 * <p>
 * While the cluster runs, Data Lifecycle Manager snapshots the volume daily, keeping the last {@link #SNAPSHOTS_KEPT}.
 * The policy goes with the cluster, as the volume only changes while the cluster runs, but its snapshots are kept.
 */
class AwsRetainedVolume {

    /** No StorageClass is of this name, so that the claims and volumes of it are only ever bound to each other. */
    static final String STORAGE_CLASS = "afon-retained";
    static final int SNAPSHOTS_KEPT = 7;
    private static final Pattern GIB = Pattern.compile("(\\d+)Gi");

    private AwsRetainedVolume() {
    }

    /**
     * @param name          what the volume is for, eg {@code gitea}, which must be a valid Terraform/Kubernetes name
     * @param volumeName    the {@code Name} tag of the volume, eg {@code gitea-full-emrys-cluster}
     * @param zone          the zone of the volume, which must have nodes
     * @param snapshotRole  the role Data Lifecycle Manager snapshots the volume as
     * @param clusterReady  what the PersistentVolume needs of the cluster, including that its volumes can be attached
     * @param imports       the {@code import} blocks of the stack, which the volume's is added to
     */
    static RetainedVolumeClaim create(Construct scope,
                                      String name,
                                      String volumeName,
                                      String size,
                                      String namespace,
                                      String zone,
                                      IamRole snapshotRole,
                                      List<? extends ITerraformDependable> clusterReady,
                                      List<Map<String, String>> imports) {
        int gib = gib(size);
        // Read when planning, as the import of what it finds must be known then: it must depend on nothing.
        DataAwsEbsVolumes lookup = DataAwsEbsVolumes.Builder
            .create(scope, name + "RetainedVolumeLookup")
            .filter(List.of(DataAwsEbsVolumesFilter.builder().name("tag:Name").values(List.of(volumeName)).build()))
            .build();
        lookup.overrideLogicalId(logicalId(name) + "_lookup");

        EbsVolume volume = EbsVolume.Builder
            .create(scope, name + "RetainedVolume")
            .availabilityZone(zone)
            .size(gib)
            .type("gp3")
            .encrypted(true)
            .tags(Map.of("Name", volumeName))
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(true).build())
            .build();
        volume.overrideLogicalId(logicalId(name));
        imports.add(Map.of(
            "for_each", format("${toset(data.aws_ebs_volumes.%s_lookup.ids)}", logicalId(name)),
            "to", "aws_ebs_volume." + logicalId(name),
            "id", "${each.value}"));

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

        String quantity = gib + "Gi";
        PersistentVolumeV1 persistentVolume = PersistentVolumeV1.Builder
            .create(scope, name + "RetainedPersistentVolume")
            .metadata(PersistentVolumeV1Metadata.builder().name(volumeName).build())
            .spec(List.of(PersistentVolumeV1Spec
                              .builder()
                              .capacity(Map.of("storage", quantity))
                              .accessModes(List.of("ReadWriteOnce"))
                              .persistentVolumeReclaimPolicy("Retain")
                              .storageClassName(STORAGE_CLASS)
                              .persistentVolumeSource(PersistentVolumeV1SpecPersistentVolumeSource
                                                          .builder()
                                                          .csi(PersistentVolumeV1SpecPersistentVolumeSourceCsi
                                                                   .builder()
                                                                   .driver("ebs.csi.aws.com")
                                                                   .volumeHandle(volume.getId())
                                                                   // Formatted on first mount if it has no filesystem.
                                                                   .fsType("ext4")
                                                                   .build())
                                                          .build())
                              .nodeAffinity(PersistentVolumeV1SpecNodeAffinity
                                                .builder()
                                                .required(PersistentVolumeV1SpecNodeAffinityRequired
                                                              .builder()
                                                              .nodeSelectorTerm(List.of(
                                                                  PersistentVolumeV1SpecNodeAffinityRequiredNodeSelectorTerm
                                                                      .builder()
                                                                      .matchExpressions(List.of(
                                                                          PersistentVolumeV1SpecNodeAffinityRequiredNodeSelectorTermMatchExpressions
                                                                              .builder()
                                                                              .key("topology.kubernetes.io/zone")
                                                                              .operator("In")
                                                                              .values(List.of(zone))
                                                                              .build()))
                                                                      .build()))
                                                              .build())
                                                .build())
                              .build()))
            .dependsOn(clusterReady)
            .build();

        String claimName = name + "-retained";
        PersistentVolumeClaimV1 claim = PersistentVolumeClaimV1.Builder
            .create(scope, name + "RetainedVolumeClaim")
            .metadata(PersistentVolumeClaimV1Metadata.builder().name(claimName).namespace(namespace).build())
            .spec(PersistentVolumeClaimV1Spec
                      .builder()
                      .accessModes(List.of("ReadWriteOnce"))
                      // Not the volume's size: Kubernetes refuses to resize a statically bound claim, so growing the
                      // volume would fail the apply.  It is bound regardless, as it only asks for less than it gets.
                      .resources(PersistentVolumeClaimV1SpecResources.builder()
                                                                     .requests(Map.of("storage", "1Gi"))
                                                                     .build())
                      .storageClassName(STORAGE_CLASS)
                      .volumeName(persistentVolume.getMetadata().getName())
                      .build())
            .dependsOn(List.of(persistentVolume))
            .build();
        return new RetainedVolumeClaim(claimName, claim);
    }

    /** The resources' own names, fixed so that their addresses, eg in the volume's import, are known. */
    static String logicalId(String name) {
        return "retained_" + name + "_volume";
    }

    /** EBS volumes are sized in whole GiB. */
    static int gib(String size) {
        Matcher matcher = GIB.matcher(size.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(format("Volume size '%s' is not a whole number of GiB, eg 10Gi", size));
        }
        return Integer.parseInt(matcher.group(1));
    }
}
