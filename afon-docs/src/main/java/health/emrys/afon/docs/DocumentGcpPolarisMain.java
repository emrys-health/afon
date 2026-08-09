package health.emrys.afon.docs;

import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.ops.DeployGcpPolarisMain;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.CLUSTER_NAME;

public class DocumentGcpPolarisMain extends AbstractDocumentMetadata {

    @Override
    public String getFilename() {
        return "gcp_polaris.md";
    }

    @Override
    public String getSupplementaryIntro() {
        return """
            Creates a GCP environment with a Polaris instance populated with the test catalogs
            """;
    }

    @Override
    public String getFurtherNotes() {
        Properties properties = new FilePropertyProvider().getProperties();
        return String.format("""
            # Problems running `tofu destroy`?
            If you see:
            ```
            │ Error: Error creating Network: googleapi: Error 409: The resource 'projects/afon-core/global/networks/gke-private-vpc' already exists, alreadyExists
            │\s
            │   with google_compute_network.XXX,
            ```
            then import it with:
            ```shell
            tofu import google_compute_network.XXX projects/afon-core/global/networks/gke-private-vpc
            ```
            where XXX is bespoke to you.
            You might need to delete offending providers.
            
            If you get:
            ```
            │ Error: Error waiting for Deleting Network: The subnetwork resource 'projects/afon-core/regions/europe-west2/subnetworks/gke-private-subnet' is already being used by 'projects/afon-core/zones/europe-west2-a/instanceGroups/gke-spark-cluster-default-pool-XXX'
            ```
            then, for that XXX, run:
            ```tofu
            gcloud compute instance-groups managed delete gke-spark-cluster-default-pool-XXX --zone europe-west2-a --project afon-core --quiet
            ```

            Cleanup the cluster with:
            ```
            gcloud container clusters list
            gcloud container clusters delete %s --region=europe-west2
            ```
            
            # Problems destroying the stack?
            
            Generate and run cleanup script:
            ```shell
            ./afon.sh -a exec --exec-class cdk.gcp.GcpCleanupHelper --exec-args gcp-cleanup.sh
            ```
            """,
            CLUSTER_NAME.getValueFrom(properties)
        );
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return new DeployGcpPolarisMain().stepsUsing(stepOperations);
    }

    public static void main(String[] args) {
        new Documenter(new DocumentGcpPolarisMain()).writeDocumentation();
    }
}
