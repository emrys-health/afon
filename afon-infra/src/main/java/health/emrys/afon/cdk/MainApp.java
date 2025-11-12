package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.google.container_cluster.ContainerCluster;
import com.hashicorp.cdktf.providers.google.container_cluster.ContainerClusterConfig;
import com.hashicorp.cdktf.providers.google.container_node_pool.ContainerNodePool;
import com.hashicorp.cdktf.providers.google.container_node_pool.ContainerNodePoolConfig;
import com.hashicorp.cdktf.providers.google.container_node_pool.ContainerNodePoolNodeConfig;
import com.hashicorp.cdktf.providers.google.data_google_client_config.DataGoogleClientConfig;
import com.hashicorp.cdktf.providers.google.provider.GoogleProvider;
import com.hashicorp.cdktf.providers.google.provider.GoogleProviderConfig;
import com.hashicorp.cdktf.providers.kubernetes.deployment.*;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProvider;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProviderConfig;
import software.constructs.Construct;

import java.util.Collections;
import java.util.List;

import static health.emrys.afon.infra.Defaults.CLUSTER_NAME;
import static health.emrys.afon.infra.Defaults.GCP_REGION;

public class MainApp {

    private static final String PROJECT_ID = "afon-core";

    public static class GkeK8sStack extends TerraformStack {
        public GkeK8sStack(final Construct scope, final String id) {
            super(scope, id);

            // --- 1. PROVISION GKE CLUSTER (GOOGLE PROVIDER) ---

            new GoogleProvider(this, "google", GoogleProviderConfig.builder()
                                                                   .project(PROJECT_ID)
                                                                   .region(GCP_REGION)
                                                                   .build());

            ContainerCluster gkeCluster = new ContainerCluster(this, "gkeCluster", ContainerClusterConfig.builder()
                                                                                                         .name(CLUSTER_NAME)
                                                                                                         .location(GCP_REGION)
                                                                                                         .removeDefaultNodePool(true)
                                                                                                         .initialNodeCount(1)
                                                                                                         .networkingMode("VPC_NATIVE")
                                                                                                         .build());

            new ContainerNodePool(this, "defaultNodePool", ContainerNodePoolConfig.builder()
                                                                                  .name("default-pool")
                                                                                  .location(GCP_REGION)
                                                                                  .cluster(gkeCluster.getName())
                                                                                  .nodeCount(1)
                                                                                  .nodeConfig(ContainerNodePoolNodeConfig.builder()
                                                                                                                         .machineType("e2-medium")
                                                                                                                         .build())
                                                                                  .build());

            // --- 2. RETRIEVE DYNAMIC K8S CREDENTIALS ---

            // A. Get dynamic access token using Google Client Config Data Source
            DataGoogleClientConfig clientConfig = new DataGoogleClientConfig(this, "currentClient");

            // B. Get API Server Host and CA Cert from the provisioned cluster
            String host = String.format("https://%s", gkeCluster.getEndpoint());
            String caCertificate = gkeCluster.getMasterAuth().getClusterCaCertificate();
            String accessToken = clientConfig.getAccessToken(); // <--- THIS IS YOUR TOKEN!

            // --- 3. CONFIGURE KUBERNETES PROVIDER ---

            new KubernetesProvider(this, "kubernetesProvider", KubernetesProviderConfig.builder()
                                                                                       .host(host)                                // API Server Endpoint
                                                                                       .clusterCaCertificate(caCertificate)       // Cluster CA Certificate Data (Base64)
                                                                                       .token(accessToken)                        // Dynamic Access Token
                                                                                       .build());

            // --- 4. DEPLOY KUBERNETES RESOURCE (Using the configured Kubernetes Provider) ---

        }
    }

    public static void main(String[] args) {
        final App app = new App();
        new GkeK8sStack(app, "gke-k8s-stack");
        app.synth();
    }
}