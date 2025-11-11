package health.emrys.afon.cdk;

import com.hashicorp.cdktf.*;
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

import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static health.emrys.afon.infra.Defaults.*;
import static java.lang.String.format;

public class AksKubernetesStack extends TerraformStack {
    public AksKubernetesStack(final Construct scope, final String id) {
        super(scope, id);

        NullProvider.Builder.create(this, "nullProvider")
                            .build();

        // --- Configuration Constants ---
        final String RESOURCE_GROUP_NAME = "afon_resource_group_aks";
        final String KUBERNETES_VERSION  = "1.31";
        // -------------------------------

        // Authentication should be handled via environment variables (AZURE_CLIENT_ID, etc.)
        AzurestackProviderFeatures features = AzurestackProviderFeatures.builder().build();
        AzurestackProviderConfig providerConfig = AzurestackProviderConfig.builder()
                                                                          .features(features)
                                                                          .metadataHost(AZURE_REGION)
                                                                          .build();
        AzurestackProvider.Builder.create(this, "azurestack").features(features).metadataHost("metadatahost").build();

        List<AzurermProviderFeatures> rmFeatures = List.of(
                AzurermProviderFeatures.builder().build()
                                                          );
        AzurermProvider.Builder.create(this, "azurerm").features(rmFeatures).subscriptionId("ffae1297-4c57-47e0-9a89-5583b97835cf").build();


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
                                                                                    .vmSize("Standard_A8_v2")
                                                                                    .build();
        LocalExecProvisioner updateCredentials = LocalExecProvisioner.builder()
                                                                     .when("create") // Run only when the resource is created
                                                                     .command(format(
                                                                                      "az aks get-credentials --resource-group %s --name %s --overwrite-existing",
                                                                                      resourceGroup.getName(),
                                                                                      CLUSTER_NAME)
                                                                             )
                                                                     .type("local-exec")
                                                                     .build();
        KubernetesCluster aksCluster = KubernetesCluster.Builder.create(this, "aks")
                                                                .name(CLUSTER_NAME)
                                                                .location(resourceGroup.getLocation())
                                                                .resourceGroupName(resourceGroup.getName())
                                                                .dnsPrefix(CLUSTER_NAME)
                                                                .kubernetesVersion(KUBERNETES_VERSION)
                                                                .defaultNodePool(nodePool)
                                                                .identity(identity)
                                                                .provisioners(List.of(updateCredentials))
                                                                .build();
    }

    public static void main(String[] args) {
        final App app = new App();
        new AksKubernetesStack(app, "az-polaris");
        app.synth();
    }
}