package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;

import static health.emrys.afon.ops.steps.StackAction.DEPLOY;

public class DeployGcpPolarisStackDocument extends AbstractStackDocument {

    public DeployGcpPolarisStackDocument(AfonPropertyProvider properties) {
        super(properties, DEPLOY, "gcp-polaris");
    }

    @Override
    public String getFilename() {
        return "deploy_gcp_polaris_stack.md";
    }

    @Override
    public String getSupplementaryIntro() {
        return """
                Deploys a GCP Polaris stack
                """;
    }

    @Override
    public String getFurtherNotes() {
        return String.format("""
            # Troubleshooting
            
            ### Problems running `tofu destroy`?
            If you see:
            ```
            │ Error: Error creating Network: googleapi: Error 409: The resource 'projects/%1$s/global/networks/gke-private-vpc' already exists, alreadyExists
            │   with google_compute_network.XXX,
            ```
            then import it with:
            ```shell
            tofu import google_compute_network.XXX projects/%1$s/global/networks/gke-private-vpc
            ```
            where XXX is bespoke to you.
            You might need to delete offending providers.
            
            If you get:
            ```
            │ Error: Error waiting for Deleting Network: The subnetwork resource 'projects/%1$s/regions/europe-west2/subnetworks/gke-private-subnet' is already being used by 'projects/%1$s/zones/europe-west2-a/instanceGroups/gke-spark-cluster-default-pool-XXX'
            ```
            then, for that XXX, run:
            ```shell
            gcloud compute instance-groups managed delete gke-spark-cluster-default-pool-XXX --zone europe-west2-a --project %1$s --quiet
            ```
            
            ### Problems destroying the stack?
            
            Generate and run cleanup script:
            ```shell
            afon-cli.sh generate-cleanup-script gcp-polaris
            ```
            """,gcpProjectId()
        );
    }

}