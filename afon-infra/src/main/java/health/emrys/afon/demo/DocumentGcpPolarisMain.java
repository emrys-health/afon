package health.emrys.afon.demo;

import health.emrys.afon.cdk.gcp.GcpConstruct;
import health.emrys.afon.config.AfonPropertiesLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.CLUSTER_NAME;
import static org.slf4j.LoggerFactory.getLogger;

public class DocumentGcpPolarisMain extends AbstractDocumentMetadata {

    private static final Logger log = getLogger(DocumentGcpPolarisMain.class);

    public static void main(String[] args) throws IOException {
        new Documenter(new DocumentGcpPolarisMain()).writeDocumentation();
    }

    @Override
    public String getSupplementaryIntro() {
        return """
            Creates a GCP environment with a Polaris instance populated with the test catalogs
            """;
    }

    @Override
    public String getFilename() {
        return "gcp_polaris.md";
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return DeployGcpPolarisMain.steps(stepOperations);
    }

    @Override
    public String getFurtherNotes() {
        Properties properties = new AfonPropertiesLoader().getProperties();
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
            
            If in a pickle, nuke everything with:
            ```shell
            %s
            ```
            
            Nuke the cluset with:
            ```
            gcloud container clusters list
            gcloud container clusters delete %s --region=europe-west2
            ```
            """,
             GcpConstruct.nukeGcp(),
             CLUSTER_NAME.getValueFrom(properties)
        );
    }

}
