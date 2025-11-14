package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.providers.google.compute_network.ComputeNetwork;
import com.hashicorp.cdktf.providers.google.compute_network.ComputeNetworkConfig;
import com.hashicorp.cdktf.providers.google.compute_subnetwork.ComputeSubnetwork;
import com.hashicorp.cdktf.providers.google.compute_subnetwork.ComputeSubnetworkConfig;
import com.hashicorp.cdktf.providers.google.compute_subnetwork.ComputeSubnetworkSecondaryIpRange;
import com.hashicorp.cdktf.providers.google.container_cluster.*;
import com.hashicorp.cdktf.providers.google.container_node_pool.*;
import com.hashicorp.cdktf.providers.google.data_google_client_config.DataGoogleClientConfig;
import com.hashicorp.cdktf.providers.google.provider.GoogleProvider;
import com.hashicorp.cdktf.providers.google.provider.GoogleProviderConfig;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import software.constructs.Construct;

import java.util.List;

import static health.emrys.afon.infra.Defaults.CLUSTER_NAME;
import static health.emrys.afon.infra.Defaults.GCP_REGION;

/**
 * If you need to destroy by hand, run:
 * gcloud container clusters delete $CLUSTER_NAME  --zone=$REGION  --project afon-core
 */
public class GcpConstruct extends Construct implements CloudProvider {

    public static final int              MAX_NODE_COUNT = 3;
    private final       ContainerCluster gkeCluster;

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
                                                               .region(GCP_REGION).zone("europe-west2-a")
                                                               .build());

        // 2. Create the GKE Cluster

        ComputeNetwork vpc = new ComputeNetwork(this, "vpc", ComputeNetworkConfig.builder()
                                                                                 .name("gke-private-vpc")
                                                                                 .autoCreateSubnetworks(false)
                                                                                 .build());

        ComputeSubnetworkConfig subnetworkConfig = ComputeSubnetworkConfig
                .builder()
                .name("gke-private-subnet")
                .ipCidrRange("10.10.0.0/24")
                .region(GCP_REGION)
                .network(vpc.getName())
                // Enable IP Aliasing/VPC-native networking
                .privateIpGoogleAccess(true)
                .secondaryIpRange(List.of(
                        ComputeSubnetworkSecondaryIpRange.builder()
                                                         .rangeName("pods")
                                                         .ipCidrRange("10.20.0.0/16")
                                                         .build(),
                        ComputeSubnetworkSecondaryIpRange.builder()
                                                         .rangeName("services")
                                                         .ipCidrRange("10.30.0.0/20")
                                                         .build()
                                         ))
                .build();
        ComputeSubnetwork subnet = new ComputeSubnetwork(this, "subnet", subnetworkConfig);

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


        ContainerClusterReleaseChannel releaseChannel = ContainerClusterReleaseChannel
                .builder()
                .channel("REGULAR")
                .build();

        ContainerClusterClusterAutoscalingAutoProvisioningDefaults provisionDefaults = ContainerClusterClusterAutoscalingAutoProvisioningDefaults
                .builder()
                .build();
        ContainerClusterClusterAutoscaling clusterAutoscaling = ContainerClusterClusterAutoscaling
                .builder()
                .enabled(true).autoProvisioningDefaults(provisionDefaults)
                .build();
        ContainerClusterConfig clusterConfig = ContainerClusterConfig
                .builder()
                .name(CLUSTER_NAME)
                .location(GCP_REGION)
                .network(vpc.getName())
                .subnetwork(subnet.getName())
                .networkingMode("VPC_NATIVE")
                .initialNodeCount(1)
                .removeDefaultNodePool(true)
                .privateClusterConfig(ContainerClusterPrivateClusterConfig.builder()
                                                                          .enablePrivateEndpoint(false) // Allows admin access via public endpoint (consumes 1 IP)
                                                                          .enablePrivateNodes(true)     // 🔑 KEY: Ensures all node pools are private by default
                                                                          .masterIpv4CidrBlock("172.16.0.0/28")
                                                                          .build())
                .masterAuth(ContainerClusterMasterAuth
                                    .builder()
                                    .clientCertificateConfig(ContainerClusterMasterAuthClientCertificateConfig
                                                                     .builder()
                                                                     .issueClientCertificate(false)
                                                                     .build())
                                    .build())
                .deletionProtection(false)
                .provisioners(List.of(refreshCredentials))
//                .releaseChannel(releaseChannel)
//                .clusterAutoscaling(clusterAutoscaling)
                // You would typically configure VPC, subnets, and other security features here.
                .build();
        gkeCluster = new ContainerCluster(this, "gkeCluster", clusterConfig);

        // 3. Create a Separate Node Pool for Worker Nodes
        // Managing the node pool separately allows adding/removing it without recreating the cluster.
        ContainerNodePoolManagement poolManagement = ContainerNodePoolManagement
                .builder()
                .autoRepair(true)
                .autoUpgrade(false)
                .build();
        ContainerNodePoolNodeConfig nodeConfig = ContainerNodePoolNodeConfig
                .builder()
                .machineType("e2-medium")
                // Default service account email for GKE nodes
                .serviceAccount(String.format("%s-compute@developer.gserviceaccount.com", projectID))
                .oauthScopes(List.of(
                        "https://www.googleapis.com/auth/cloud-platform"
                                    ))
                .build();
        ContainerNodePoolAutoscaling autoscaling = ContainerNodePoolAutoscaling
                .builder()
//                .minNodeCount(1)
//                .maxNodeCount(MAX_NODE_COUNT)
                .totalMaxNodeCount(MAX_NODE_COUNT)
                .totalMinNodeCount(1)
                .build();
//        ContainerNodePoolNetworkConfig netConfig = ContainerNodePoolNetworkConfig
//                .builder()
//                .enablePrivateNodes(false)
//                .podIpv4CidrBlock("10.16.0.0/14")
//                .additionalNodeNetworkConfigs(List.of(ContainerNodePoolNetworkConfigAdditionalNodeNetworkConfigs
//                                                              .builder()
//                                                              .network("projects/afon-core/regions/europe-west2/subnetworks/default")
//                                                              .build()))
////                .podRange()
//                .build();
        new ContainerNodePool(this, "defaultNodePool", ContainerNodePoolConfig
                .builder()
                .name("default-pool")
                .location(GCP_REGION)
                .cluster(gkeCluster.getName())
                .nodeCount(MAX_NODE_COUNT) // Specify desired number of nodes
                .nodeConfig(nodeConfig)
                .networkConfig(ContainerNodePoolNetworkConfig
                                       .builder()
                                       // ENSURE: This setting overrides the cluster default if needed, guaranteeing no public IP
                                       .enablePrivateNodes(true)
                                       // Use the Pod secondary range name (Index 0 in the list above)
                                       .podRange(subnet.getSecondaryIpRange().get(0).getRangeName())
                                       .podIpv4CidrBlock(subnet.getSecondaryIpRange().get(0).getIpCidrRange())
                                       .createPodRange(true)
                                       .build())
//                .management(poolManagement)
//                .networkConfig(netConfig)
//                .autoscaling(autoscaling).project(PROJECT_ID)
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
