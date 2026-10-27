package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
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
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.String.format;

/**
 * A disk that outlives the cluster, bound statically to a claim, see {@link CloudPlatform#retainedVolumeClaim}.  Subclasses give
 * the cloud's disk, and the CSI driver that attaches it.
 * <p>
 * The disk is named, after what it is for and the cluster, eg {@code gitea-full-emrys-cluster}.  If a disk of that name exists, it
 * is imported into the state, else it is created: afon deletes the state with the stack, so a cluster of the same name finds the
 * disk again by its name.  It has {@code prevent_destroy}, which afon's destroy excludes it by, so that it is kept, while a plain
 * {@code tofu destroy} fails rather than deletes it.
 * <p>
 * The PersistentVolume of the cluster points at the disk, with the reclaim policy {@code Retain}, and is bound to the claim by
 * name, of a storage class that no StorageClass is, so that no provisioner ever acts on either.
 */
public abstract class RetainedVolume {

    /** No StorageClass is of this name, so that the claims and volumes of it are only ever bound to each other. */
    public static final String STORAGE_CLASS = "afon-retained";

    private static final Pattern GIB = Pattern.compile("(\\d+)Gi");

    /**
     * The disk, as the subclass created it.
     *
     * @param id            the id the CSI driver attaches it by, ie the volume handle, as a reference resolved when the plan is applied
     * @param prerequisites what must exist before the disk can be attached, besides the cluster, eg the grant to attach it
     */
    public record Volume(String id, List<? extends ITerraformDependable> prerequisites) {
    }

    /** What the volume is for, eg {@code gitea}, which must be a valid Terraform/Kubernetes name. */
    protected final String name;
    /** The name of the disk and of the PersistentVolume, eg {@code gitea-full-emrys-cluster}. */
    protected final String volumeName;
    /** The size in whole GiB. */
    protected final int    gib;
    private final   String namespace;

    /**
     * @param size      the size of the disk, as Kubernetes quantities of GiB, eg {@code 10Gi}
     * @param namespace the namespace of the claim
     * @throws IllegalArgumentException if the size is not a whole number of GiB
     */
    protected RetainedVolume(String name, String volumeName, String size, String namespace) {
        this.name       = name;
        this.volumeName = volumeName;
        this.gib        = gib(size);
        this.namespace  = namespace;
    }

    /**
     * Creates the disk, if there is none of its name, and the volume and claim for it.
     *
     * @param clusterReady what the PersistentVolume needs of the cluster, including that its volumes can be attached
     * @param imports      the {@code import} blocks of the stack, which the disk's is added to
     */
    public RetainedVolumeClaim deploy(Construct scope,
                                      List<? extends ITerraformDependable> clusterReady,
                                      List<Map<String, String>> imports) {
        Volume volume = createVolume(scope, imports);
        List<ITerraformDependable> ready = new ArrayList<>(clusterReady);
        ready.addAll(volume.prerequisites());

        PersistentVolumeV1 persistentVolume = PersistentVolumeV1.Builder
            .create(scope, name + "RetainedPersistentVolume")
            .metadata(PersistentVolumeV1Metadata.builder().name(volumeName).build())
            .spec(List.of(persistentVolumeSpec(volume)))
            .dependsOn(ready)
            .build();

        String claimName = name + "-retained";
        PersistentVolumeClaimV1 claim = PersistentVolumeClaimV1.Builder
            .create(scope, name + "RetainedVolumeClaim")
            .metadata(PersistentVolumeClaimV1Metadata.builder().name(claimName).namespace(namespace).build())
            .spec(PersistentVolumeClaimV1Spec
                      .builder()
                      .accessModes(List.of("ReadWriteOnce"))
                      // Not the disk's size: Kubernetes refuses to resize a statically bound claim, so growing the
                      // disk would fail the apply.  It is bound regardless, as it only asks for less than it gets.
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

    /**
     * Creates the disk, which must have {@code prevent_destroy} and the {@link #logicalId() logical id}, a lookup of the disks of its
     * name that depends on nothing, as it is read when planning, and its import, see {@link #importIfExists}, and anything that goes
     * with it, eg snapshots.
     */
    protected abstract Volume createVolume(Construct scope, List<Map<String, String>> imports);

    /** @return the CSI driver that attaches the disk, eg {@code ebs.csi.aws.com} */
    protected abstract String csiDriver();

    /** @return the zone of the disk, if it is zonal, in which case its pods are scheduled to nodes there */
    protected Optional<String> zone() {
        return Optional.empty();
    }

    /**
     * Adds the import of the disk, if there is one of its name: the {@code for_each} runs over the ids that the lookup found, none
     * if it found none.
     *
     * @param resourceType the type of the resource of the disk, eg {@code aws_ebs_volume}
     * @param ids          the reference to the ids that the lookup found, eg {@code data.aws_ebs_volumes.retained_gitea_volume_lookup.ids}
     */
    protected void importIfExists(List<Map<String, String>> imports, String resourceType, String ids) {
        imports.add(Map.of(
            "for_each", format("${toset(%s)}", ids),
            "to", resourceType + "." + logicalId(),
            "id", "${each.value}"));
    }

    private PersistentVolumeV1Spec persistentVolumeSpec(Volume volume) {
        PersistentVolumeV1Spec.Builder spec = PersistentVolumeV1Spec
            .builder()
            .capacity(Map.of("storage", gib + "Gi"))
            .accessModes(List.of("ReadWriteOnce"))
            .persistentVolumeReclaimPolicy("Retain")
            .storageClassName(STORAGE_CLASS)
            .persistentVolumeSource(PersistentVolumeV1SpecPersistentVolumeSource
                                        .builder()
                                        .csi(PersistentVolumeV1SpecPersistentVolumeSourceCsi
                                                 .builder()
                                                 .driver(csiDriver())
                                                 .volumeHandle(volume.id())
                                                 // Formatted on first mount if it has no filesystem.
                                                 .fsType("ext4")
                                                 .build())
                                        .build());
        zone().ifPresent(zone -> spec.nodeAffinity(PersistentVolumeV1SpecNodeAffinity
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
                                                       .build()));
        return spec.build();
    }

    /** @return the resources' own names, fixed so that their addresses, eg in the disk's import, are known */
    protected String logicalId() {
        return logicalId(name);
    }

    public static String logicalId(String name) {
        return "retained_" + name + "_volume";
    }

    /** Disks are sized in whole GiB. */
    public static int gib(String size) {
        Matcher matcher = GIB.matcher(size.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(format("Volume size '%s' is not a whole number of GiB, eg 10Gi", size));
        }
        return Integer.parseInt(matcher.group(1));
    }
}
