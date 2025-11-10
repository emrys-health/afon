package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.AzurermBackend;
import com.hashicorp.cdktf.AzurermBackendConfig;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.azurerm.kubernetes_cluster.KubernetesCluster;
import com.hashicorp.cdktf.providers.azurerm.kubernetes_cluster.KubernetesClusterConfig;
import com.hashicorp.cdktf.providers.azurerm.kubernetes_cluster.KubernetesClusterDefaultNodePool;
import com.hashicorp.cdktf.providers.azurerm.kubernetes_cluster.KubernetesClusterIdentity;
import com.hashicorp.cdktf.providers.azurerm.provider.AzurermProvider;
import com.hashicorp.cdktf.providers.azurerm.provider.AzurermProviderFeatures;
import com.hashicorp.cdktf.providers.azurerm.provider.AzurermProviderFeaturesSubscription;
import com.hashicorp.cdktf.providers.azurerm.subscription.Subscription;
import com.hashicorp.cdktf.providers.azurestack.provider.AzurestackProvider;
import com.hashicorp.cdktf.providers.azurestack.provider.AzurestackProviderConfig;
import com.hashicorp.cdktf.providers.azurestack.provider.AzurestackProviderFeatures;
import com.hashicorp.cdktf.providers.azurestack.resource_group.ResourceGroup;
import com.hashicorp.cdktf.providers.azurestack.resource_group.ResourceGroupConfig;
import com.hashicorp.cdktf.providers.kubernetes.namespace.Namespace;
import com.hashicorp.cdktf.providers.kubernetes.namespace.NamespaceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProvider;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProviderConfig;
import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import software.constructs.Construct;

import java.util.List;
import java.util.Properties;

import static health.emrys.afon.infra.Defaults.AZURE_REGION;
import static health.emrys.afon.infra.Defaults.CLUSTER_NAME;

public class AksKubernetesStack extends TerraformStack {
    public AksKubernetesStack(final Construct scope, final String id) {
        super(scope, id);

        NullProvider.Builder.create(this, "nullProvider")
                            .build();

        // --- Configuration Constants ---
        final String RESOURCE_GROUP_NAME = "afon_resource_group";
        final String KUBERNETES_VERSION  = "1.31";
        // -------------------------------

        // 1. Configure the Azure Provider (azurerm)
        // Authentication should be handled via environment variables (AZURE_CLIENT_ID, etc.)
        AzurestackProviderFeatures features = AzurestackProviderFeatures.builder().build();
        AzurestackProviderConfig providerConfig = AzurestackProviderConfig.builder()
                                                                          .features(features)
                                                                          .metadataHost(AZURE_REGION)
                                                                          .build();
//        new AzurestackProvider(this, "azurerm", providerConfig);
        AzurestackProvider.Builder.create(this, "azurestack").features(features).metadataHost("metadatahost").build();

//        var rmFeatures = AzurermProvider.Builder.create(this, "azure-features").features(List.of()).build();
//        Properties properties = new AfonPropertiesLoader().getProperties();
//        Subscription subscription = Subscription.Builder.create(this, "subscription").id("b0cf8b88-d564-4245-b609-498635346319").build();
        List<AzurermProviderFeatures> rmFeatures = List.of(
//                AzurermProviderFeatures.builder().subscription(List.of(subscription)).build()
                AzurermProviderFeatures.builder().build()
                                                          );
        AzurermProvider.Builder.create(this, "azurerm").features(rmFeatures).subscriptionId("ffae1297-4c57-47e0-9a89-5583b97835cf").build();

//        AzurermBackend.Builder.create(this).containerName("container-name")
//                                                     .key("container-key")
//                                                    .storageAccountName("afonstorageaccount")
//                                                     .build();

//        new AzurermBackend(this, AzurermBackendConfig.builder()
//                                                     .containerName("container-name")
//                                                     .key("container-key")
//                                                    .storageAccountName("afonstorageaccount")
//                                                     .build());

        // 2. Create Azure Resource Group
        ResourceGroup resourceGroup = new ResourceGroup(this, "aks-rg", ResourceGroupConfig.builder()
                                                                                           .name(RESOURCE_GROUP_NAME)
                                                                                           .location(AZURE_REGION)
                                                                                           .build());

        // 3. Create Azure Kubernetes Service (AKS) Cluster
        KubernetesClusterIdentity identity = KubernetesClusterIdentity.builder()
                                                                      .type("SystemAssigned")
                                                                      .build();
        KubernetesClusterDefaultNodePool nodePool = KubernetesClusterDefaultNodePool.builder()
                                                                                    .name("systempool")
                                                                                    .nodeCount(1)
                                                                                    .vmSize("Standard_DS2_v2")
                                                                                    .build();
        KubernetesCluster aksCluster = KubernetesCluster.Builder.create(this, "aks")
                                                                .name(CLUSTER_NAME)
                                                                .location(resourceGroup.getLocation())
                                                                .resourceGroupName(resourceGroup.getName())
                                                                .dnsPrefix(CLUSTER_NAME)
                                                                .kubernetesVersion(KUBERNETES_VERSION)
                                                                .defaultNodePool(nodePool)
                                                                .identity(identity)
                                                                .build();

//        KubernetesCluster aksCluster = new KubernetesCluster(this, "aks-cluster", clusterConfig);

//        KubernetesProvider.Builder.create(this, "kubernetes")
//                                  .host(aksCluster.getKubeConfig().get(0).getHost())
////                                  .clusterCaCertificate(encodedCert)
////                                  .token(cloudProvider.getToken()) // Dynamically generated token
//                                  .build();

//
//        // 5. Deploy a Resource using the Kubernetes Provider (Example: a Namespace)
//        new Namespace(this, "cdktf-aks-namespace", com.hashicorp.cdktf.providers.kubernetes.namespace.NamespaceConfig.builder()
//                                                                                                                     .metadata(
//                                                                                                                             NamespaceMetadata.builder()
//                                                                                                                                              .name("cdktf-azure-namespace")
//                                                                                                                                              .build())
//                                                                                                                     .build());
    }

    public static void main(String[] args) {
        final App app = new App();
        new AksKubernetesStack(app, "az-polaris");
        app.synth();
    }
}