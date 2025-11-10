package health.emrys.afon.cdk;

import com.hashicorp.cdktf.providers.google.container_cluster.ContainerClusterReleaseChannel;
import com.hashicorp.cdktf.providers.google.container_node_pool.ContainerNodePoolManagement;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.google.provider.GoogleProvider;
import com.hashicorp.cdktf.providers.google.provider.GoogleProviderConfig;
import com.hashicorp.cdktf.providers.google.container_cluster.ContainerCluster;
import com.hashicorp.cdktf.providers.google.container_cluster.ContainerClusterConfig;
import com.hashicorp.cdktf.providers.google.container_node_pool.ContainerNodePool;
import com.hashicorp.cdktf.providers.google.container_node_pool.ContainerNodePoolConfig;
import com.hashicorp.cdktf.providers.google.container_node_pool.ContainerNodePoolNodeConfig;
import software.constructs.Construct;

import java.util.Collections;
import java.util.List;

import java.util.List;

import static health.emrys.afon.infra.Defaults.CLUSTER_NAME;
import static health.emrys.afon.infra.Defaults.GCP_REGION;

public class GcpConstruct extends Construct implements CloudProvider {

    private final ContainerCluster gkeCluster;

    GcpConstruct(final Construct scope, final String id, String projectID) {
        super(scope, id);
        // 1. Configure Google Provider
        new GoogleProvider(this, "google", GoogleProviderConfig.builder()
                                                               .project(projectID)
                                                               .region(GCP_REGION)
                                                               .build());

        // 2. Create the GKE Cluster
        // It's best practice to disable the default node pool to manage node pools separately.
        ContainerClusterReleaseChannel releaseChannel = ContainerClusterReleaseChannel.builder().channel("REGULAR").build();
        ContainerClusterConfig clusterConfig = ContainerClusterConfig.builder()
                                                                     .name(CLUSTER_NAME)
                                                                     .location(GCP_REGION)
                                                                     // Disable the default node pool to manage it with the dedicated resource below
                                                                     .removeDefaultNodePool(true)
                                                                     .initialNodeCount(1) // Required, but the default will be deleted

                                                                     // Recommended configuration for Autopilot/Standard GKE Networking
                                                                     .networkingMode("VPC_NATIVE")

                                                                     // Enable certain features
                                                                     .releaseChannel(releaseChannel)

                                                                     // You would typically configure VPC, subnets, and other security features here.
                                                                     .build();
        gkeCluster = new ContainerCluster(this, "gkeCluster", clusterConfig);
        //

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
    public List<Resource> getDependables() {
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
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void configureLoadBalancerDestroy(List<Service> loadBalancers) {
        throw new UnsupportedOperationException("Not supported yet.");
    }
}
