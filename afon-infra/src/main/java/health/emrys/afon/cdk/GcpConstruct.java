package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.providers.google.container_cluster.ContainerCluster;
import com.hashicorp.cdktf.providers.google.container_cluster.ContainerClusterConfig;
import com.hashicorp.cdktf.providers.google.container_cluster.ContainerClusterReleaseChannel;
import com.hashicorp.cdktf.providers.google.container_node_pool.ContainerNodePool;
import com.hashicorp.cdktf.providers.google.container_node_pool.ContainerNodePoolConfig;
import com.hashicorp.cdktf.providers.google.container_node_pool.ContainerNodePoolManagement;
import com.hashicorp.cdktf.providers.google.container_node_pool.ContainerNodePoolNodeConfig;
import com.hashicorp.cdktf.providers.google.data_google_client_config.DataGoogleClientConfig;
import com.hashicorp.cdktf.providers.google.provider.GoogleProvider;
import com.hashicorp.cdktf.providers.google.provider.GoogleProviderConfig;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import software.constructs.Construct;

import java.util.List;

import static health.emrys.afon.infra.Defaults.CLUSTER_NAME;
import static health.emrys.afon.infra.Defaults.GCP_REGION;

public class GcpConstruct extends Construct implements CloudProvider {

    private final ContainerCluster gkeCluster;

    /**
     * Generate with:
     * gcloud projects list --format="table(projectId,name)"
     */
    private final String PROJECT_ID = "afon-core";

    GcpConstruct(final Construct scope, final String id, String projectID) {
        super(scope, id);
        // 1. Configure Google Provider
        new GoogleProvider(this, "google", GoogleProviderConfig.builder()
                                                               .project(projectID)
                                                               .region(GCP_REGION)
                                                               .build());

        // 2. Create the GKE Cluster
        // It's best practice to disable the default node pool to manage node pools separately.

        String gcloudCommand = String.format(
                "gcloud container clusters get-credentials %s --region %s --project %s",
                CLUSTER_NAME,
                GCP_REGION,
                PROJECT_ID
                                            );
        // Attach the LocalExec Provisioner
        LocalExecProvisioner refreshCredentials = LocalExecProvisioner.builder()
                                                                      .command(gcloudCommand)
                                                                      // Optionally specify the interpreter for security/compatibility
                                                                      .interpreter(List.of("/bin/bash", "-c"))
                                                                      .type("local-exec")
                                                                      .build();


        ContainerClusterReleaseChannel releaseChannel = ContainerClusterReleaseChannel.builder().channel("REGULAR").build();
        ContainerClusterConfig clusterConfig = ContainerClusterConfig.builder()
                                                                     .name(CLUSTER_NAME)
                                                                     .location(GCP_REGION)
                                                                     .removeDefaultNodePool(true)
                                                                     .initialNodeCount(1)
                                                                     // Recommended configuration for Autopilot/Standard GKE Networking
                                                                     .networkingMode("VPC_NATIVE")
                                                                     .provisioners(List.of(refreshCredentials))
                                                                     .releaseChannel(releaseChannel)
                                                                     // You would typically configure VPC, subnets, and other security features here.
                                                                     .build();
        gkeCluster = new ContainerCluster(this, "gkeCluster", clusterConfig);

        // 3. Create a Separate Node Pool for Worker Nodes
        // Managing the node pool separately allows adding/removing it without recreating the cluster.
        ContainerNodePoolManagement poolManagement = ContainerNodePoolManagement.builder().autoRepair(true).autoUpgrade(true).build();
        ContainerNodePoolNodeConfig nodeConfig = ContainerNodePoolNodeConfig.builder()
                                                                            .machineType("e2-medium")
                                                                            // Default service account email for GKE nodes
                                                                            .serviceAccount(String.format("%s-compute@developer.gserviceaccount.com", projectID))
                                                                            .oauthScopes(List.of(
                                                                                    "https://www.googleapis.com/auth/cloud-platform"
                                                                                                ))
                                                                            .build();
        new ContainerNodePool(this, "defaultNodePool", ContainerNodePoolConfig.builder()
                                                                              .name("default-pool")
                                                                              .location(GCP_REGION)
                                                                              .cluster(gkeCluster.getName())
                                                                              .nodeCount(3) // Specify desired number of nodes
                                                                              .nodeConfig(nodeConfig)
                                                                              .management(poolManagement)
                                                                              .build());
        // 4. Output the Cluster Endpoint
        com.hashicorp.cdktf.TerraformOutput.Builder.create(this, "gkeEndpoint")
                                                   .value(gkeCluster.getEndpoint())
                                                   .build();

    }

    @Override
    public List<ITerraformDependable> getDependables() {
        return List.of();
    }

    @Override
    public String getBase64CertData() {
        return gkeCluster.getMasterAuth().getClusterCaCertificate();
    }

    @Override
    public String getClusterEndpoint() {
        return gkeCluster.getEndpoint();
    }

    @Override
    public String getToken() {
        DataGoogleClientConfig clientConfig = new DataGoogleClientConfig(this, "currentClient");
        return clientConfig.getAccessToken();
    }

    @Override
    public void configureLoadBalancerDestroy(List<Service> loadBalancers) {
        throw new UnsupportedOperationException("Not supported yet.");
    }
}
