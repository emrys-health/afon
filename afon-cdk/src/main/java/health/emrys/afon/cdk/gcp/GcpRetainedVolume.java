package health.emrys.afon.cdk.gcp;

import com.hashicorp.cdktf.providers.google.data_google_compute_disk.DataGoogleComputeDisk;
import health.emrys.afon.cdk.RetainedVolume;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.lang.String.format;

/**
 * A Persistent Disk that outlives the cluster, see {@link RetainedVolume}, named by its name.
 * <p>
 * Unlike on AWS and Azure the stack does not create the disk, as the Google provider has no data source that lists disks, which
 * is how those find whether one exists to import: the disk is created beforehand by {@code gcp-create}, as is the static IP of
 * {@code GCP_EGRESS_IP}, and deleted by {@code gcp-destroy}, so the stack only looks it up, and fails to plan if there is none.  So
 * deleting the stack never deletes it.  The disk is zonal, so the pods that use it are scheduled to nodes in its zone.
 */
class GcpRetainedVolume extends RetainedVolume {

    private final String project;
    private final String zone;

    /**
     * @param volumeName the name of the disk, eg {@code gitea-full-emrys}
     * @param project    the project of the disk
     * @param zone       the zone of the disk, eg {@code europe-west2-a}
     */
    GcpRetainedVolume(String name, String volumeName, String size, String namespace, String project, String zone) {
        super(name, volumeName, size, namespace);
        this.project = project;
        this.zone    = zone;
    }

    @Override
    protected Volume createVolume(Construct scope, List<Map<String, String>> imports) {
        DataGoogleComputeDisk disk = DataGoogleComputeDisk.Builder
            .create(scope, name + "RetainedVolumeLookup")
            .name(volumeName)
            .project(project)
            .zone(zone)
            .build();
        // the handle the CSI driver attaches the disk by, which is the id of the disk
        return new Volume(format("projects/%s/zones/%s/disks/%s", project, zone, volumeName), List.of(disk));
    }

    @Override
    protected String csiDriver() {
        return "pd.csi.storage.gke.io";
    }

    @Override
    protected Optional<String> zone() {
        return Optional.of(zone);
    }
}
