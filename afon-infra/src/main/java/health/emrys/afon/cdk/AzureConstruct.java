package health.emrys.afon.cdk;

import com.hashicorp.cdktf.Fn;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.providers.azurerm.kubernetes_cluster.KubernetesCluster;
import com.hashicorp.cdktf.providers.azurerm.kubernetes_cluster.KubernetesClusterDefaultNodePool;
import com.hashicorp.cdktf.providers.azurerm.kubernetes_cluster.KubernetesClusterIdentity;
import com.hashicorp.cdktf.providers.azurerm.kubernetes_cluster.KubernetesClusterNetworkProfile;
import com.hashicorp.cdktf.providers.azurerm.kubernetes_cluster.KubernetesClusterTimeouts;
import com.hashicorp.cdktf.providers.azurerm.network_security_group.NetworkSecurityGroup;
import com.hashicorp.cdktf.providers.azurerm.network_security_group.NetworkSecurityGroupConfig;
import com.hashicorp.cdktf.providers.azurerm.network_security_group.NetworkSecurityGroupSecurityRule;
import com.hashicorp.cdktf.providers.azurerm.provider.AzurermProvider;
import com.hashicorp.cdktf.providers.azurerm.provider.AzurermProviderFeatures;
import com.hashicorp.cdktf.providers.azurerm.role_assignment.RoleAssignment;
import com.hashicorp.cdktf.providers.azurerm.role_assignment.RoleAssignmentConfig;
import com.hashicorp.cdktf.providers.azurerm.user_assigned_identity.UserAssignedIdentity;
import com.hashicorp.cdktf.providers.azurerm.user_assigned_identity.UserAssignedIdentityConfig;
import com.hashicorp.cdktf.providers.azurerm.subnet.Subnet;
import com.hashicorp.cdktf.providers.azurerm.subnet.SubnetConfig;
import com.hashicorp.cdktf.providers.azurerm.subnet_network_security_group_association.SubnetNetworkSecurityGroupAssociation;
import com.hashicorp.cdktf.providers.azurerm.subnet_network_security_group_association.SubnetNetworkSecurityGroupAssociationConfig;
import com.hashicorp.cdktf.providers.azurerm.virtual_network.VirtualNetwork;
import com.hashicorp.cdktf.providers.azurerm.virtual_network.VirtualNetworkConfig;
import com.hashicorp.cdktf.providers.azurestack.provider.AzurestackProvider;
import com.hashicorp.cdktf.providers.azurestack.provider.AzurestackProviderFeatures;
import com.hashicorp.cdktf.providers.azurestack.resource_group.ResourceGroup;
import com.hashicorp.cdktf.providers.azurestack.resource_group.ResourceGroupConfig;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.provider.HelmProviderKubernetes;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.helm.release.ReleaseSet;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProvider;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import org.jetbrains.annotations.NotNull;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

import static health.emrys.afon.infra.Defaults.AZURE_REGION;
import static health.emrys.afon.infra.Defaults.CLUSTER_NAME;
import static java.lang.String.format;

public class AzureConstruct extends AbstractConstruct {

    private static final String RESOURCE_GROUP_NAME = "afon_resource_group_aks";
    private static final String KUBERNETES_VERSION   = "1.32";
    private static final String VNET_CIDR            = "10.1.0.0/16";
    private static final String SUBNET_CIDR          = "10.1.1.0/24";
    private static final String SERVICE_CIDR         = "10.2.0.0/16";
    private static final String DNS_SERVICE_IP       = "10.2.0.10";
    private static final String SUBSCRIPTION_ID      = "ffae1297-4c57-47e0-9a89-5583b97835cf";
    private static final String ACME_EMAIL           = "admin@emryspolarisazure.click";

    private final KubernetesCluster aksCluster;
    private KubernetesProvider      k8sProvider;
    private HelmProvider            helmProvider;

    public AzureConstruct(@NotNull Construct scope, @NotNull String id) {
        super(scope, id);
        configureProviders();
        ResourceGroup        resourceGroup = createResourceGroup();
        UserAssignedIdentity identity      = createIdentity(resourceGroup);
        Subnet               nodeSubnet    = createNetworking(resourceGroup, identity);
        aksCluster = createCluster(resourceGroup, nodeSubnet, identity);
    }

    // -------------------------------------------------------------------------
    // Providers
    // -------------------------------------------------------------------------

    private void configureProviders() {
        NullProvider.Builder.create(this, "null").build();
        AzurestackProvider.Builder.create(this, "azurestack")
                                  .features(AzurestackProviderFeatures.builder().build())
                                  .metadataHost(AZURE_REGION)
                                  .build();
        AzurermProvider.Builder.create(this, "azurerm")
                               .features(List.of(AzurermProviderFeatures.builder().build()))
                               .subscriptionId(SUBSCRIPTION_ID)
                               .build();
    }

    // -------------------------------------------------------------------------
    // Resource group
    // -------------------------------------------------------------------------

    private ResourceGroup createResourceGroup() {
        return new ResourceGroup(this, "aks-rg", ResourceGroupConfig.builder()
                .name(RESOURCE_GROUP_NAME)
                .location(AZURE_REGION)
                .build());
    }

    // -------------------------------------------------------------------------
    // Managed identity (created before the cluster so we can pre-grant roles)
    // -------------------------------------------------------------------------

    private UserAssignedIdentity createIdentity(ResourceGroup resourceGroup) {
        return new UserAssignedIdentity(this, "aks-identity", UserAssignedIdentityConfig.builder()
                .name("aks-identity")
                .location(resourceGroup.getLocation())
                .resourceGroupName(resourceGroup.getName())
                .build());
    }

    // -------------------------------------------------------------------------
    // Networking: VNet → Subnet → NSG → association → role assignment
    // -------------------------------------------------------------------------

    private Subnet createNetworking(ResourceGroup resourceGroup, UserAssignedIdentity identity) {
        VirtualNetwork vnet = new VirtualNetwork(this, "aks-vnet", VirtualNetworkConfig.builder()
                .name("aks-vnet")
                .location(resourceGroup.getLocation())
                .resourceGroupName(resourceGroup.getName())
                .addressSpace(List.of(VNET_CIDR))
                .build());

        NetworkSecurityGroup nsg = createNsg(resourceGroup);

        Subnet subnet = new Subnet(this, "aks-subnet", SubnetConfig.builder()
                .name("aks-subnet")
                .resourceGroupName(resourceGroup.getName())
                .virtualNetworkName(vnet.getName())
                .addressPrefixes(List.of(SUBNET_CIDR))
                .build());

        new SubnetNetworkSecurityGroupAssociation(this, "aks-nsg-assoc",
                SubnetNetworkSecurityGroupAssociationConfig.builder()
                        .subnetId(subnet.getId())
                        .networkSecurityGroupId(nsg.getId())
                        .build());

        new RoleAssignment(this, "aks-identity-network-contributor", RoleAssignmentConfig.builder()
                .principalId(identity.getPrincipalId())
                .roleDefinitionName("Network Contributor")
                .scope(subnet.getId())
                .build());

        return subnet;
    }

    private NetworkSecurityGroup createNsg(ResourceGroup resourceGroup) {
        // Health probes from the Azure Load Balancer infrastructure
        NetworkSecurityGroupSecurityRule lbInbound = NetworkSecurityGroupSecurityRule.builder()
                .name("AllowAzureLoadBalancerInbound")
                .priority(100)
                .direction("Inbound")
                .access("Allow")
                .protocol("*")
                .sourcePortRange("*")
                .destinationPortRange("*")
                .sourceAddressPrefix("AzureLoadBalancer")
                .destinationAddressPrefix("*")
                .build();

        // The subnet NSG is evaluated post-DNAT (destination = node private IP : NodePort),
        // so we need the NodePort range here.
        // The NIC NSG is evaluated pre-DNAT (destination = LB frontend IP : 80/443),
        // which is why the AKS cloud controller creates NIC rules using the LB frontend IP —
        // those are handled automatically by AKS.
        NetworkSecurityGroupSecurityRule httpInbound = NetworkSecurityGroupSecurityRule.builder()
                .name("AllowHttpInbound")
                .priority(110)
                .direction("Inbound")
                .access("Allow")
                .protocol("Tcp")
                .sourcePortRange("*")
                .destinationPortRange("80")
                .sourceAddressPrefix("Internet")
                .destinationAddressPrefix("*")
                .build();

        NetworkSecurityGroupSecurityRule httpsInbound = NetworkSecurityGroupSecurityRule.builder()
                .name("AllowHttpsInbound")
                .priority(120)
                .direction("Inbound")
                .access("Allow")
                .protocol("Tcp")
                .sourcePortRange("*")
                .destinationPortRange("443")
                .sourceAddressPrefix("Internet")
                .destinationAddressPrefix("*")
                .build();

        // Belt-and-suspenders: also allow NodePort range in case the subnet NSG
        // sees post-DNAT traffic (node IP : NodePort) rather than the LB frontend IP.
        NetworkSecurityGroupSecurityRule nodePortInbound = NetworkSecurityGroupSecurityRule.builder()
                .name("AllowNodePortInbound")
                .priority(130)
                .direction("Inbound")
                .access("Allow")
                .protocol("Tcp")
                .sourcePortRange("*")
                .destinationPortRange("30000-32767")
                .sourceAddressPrefix("Internet")
                .destinationAddressPrefix("*")
                .build();

        NetworkSecurityGroupSecurityRule internetOutbound = NetworkSecurityGroupSecurityRule.builder()
                .name("AllowInternetOutbound")
                .priority(100)
                .direction("Outbound")
                .access("Allow")
                .protocol("*")
                .sourcePortRange("*")
                .destinationPortRange("*")
                .sourceAddressPrefix("*")
                .destinationAddressPrefix("Internet")
                .build();

        NetworkSecurityGroupSecurityRule outbound = NetworkSecurityGroupSecurityRule.builder()
                .name("AllowAzureLoadBalancerOutbound")
                .priority(110)
                .direction("Outbound")
                .access("Allow")
                .protocol("*")
                .sourcePortRange("*")
                .destinationPortRange("*")
                .sourceAddressPrefix("AzureLoadBalancer")
                .destinationAddressPrefix("*")
                .build();

        return new NetworkSecurityGroup(this, "aks-nsg", NetworkSecurityGroupConfig.builder()
                .name("aks-nsg")
                .location(resourceGroup.getLocation())
                .resourceGroupName(resourceGroup.getName())
                .securityRule(List.of(lbInbound, httpInbound, httpsInbound, nodePortInbound, internetOutbound, outbound))
                .build());
    }

    // -------------------------------------------------------------------------
    // AKS cluster
    // -------------------------------------------------------------------------

    private KubernetesCluster createCluster(ResourceGroup resourceGroup,
                                            Subnet nodeSubnet,
                                            UserAssignedIdentity identity) {
        KubernetesClusterDefaultNodePool nodePool = KubernetesClusterDefaultNodePool.builder()
                .name("systempool")
                .nodeCount(1)
                .vmSize("Standard_A8_v2")
                .vnetSubnetId(nodeSubnet.getId())
                .build();

        KubernetesClusterNetworkProfile networkProfile = KubernetesClusterNetworkProfile.builder()
                .networkPlugin("azure")
                .loadBalancerSku("standard")
                .serviceCidr(SERVICE_CIDR)
                .dnsServiceIp(DNS_SERVICE_IP)
                .build();

        LocalExecProvisioner updateCredentials = LocalExecProvisioner.builder()
                .when("create")
                .command(format("az aks get-credentials --resource-group %s --name %s --overwrite-existing",
                                resourceGroup.getName(), CLUSTER_NAME))
                .type("local-exec")
                .build();

        return KubernetesCluster.Builder.create(this, "aks")
                                        .name(CLUSTER_NAME)
                                        .location(resourceGroup.getLocation())
                                        .resourceGroupName(resourceGroup.getName())
                                        .dnsPrefix(CLUSTER_NAME)
                                        .kubernetesVersion(KUBERNETES_VERSION)
                                        .defaultNodePool(nodePool)
                                        .identity(KubernetesClusterIdentity.builder()
                                                                            .type("UserAssigned")
                                                                            .identityIds(List.of(identity.getId()))
                                                                            .build())
                                        .networkProfile(networkProfile)
                                        .timeouts(KubernetesClusterTimeouts.builder()
                                                                           .create("20m")
                                                                           .build())
                                        .provisioners(List.of(updateCredentials))
                                        .build();
    }

    // -------------------------------------------------------------------------
    // AbstractConstruct implementation
    // -------------------------------------------------------------------------

    @Override
    public synchronized KubernetesProvider configureK8s() {
        if (k8sProvider == null) {
            var cfg = aksCluster.getKubeConfig().get(0);
            k8sProvider = KubernetesProvider.Builder.create(this, "kubernetes")
                                                    .host(cfg.getHost())
                                                    .clusterCaCertificate(Fn.base64decode(cfg.getClusterCaCertificate()))
                                                    .clientCertificate(Fn.base64decode(cfg.getClientCertificate()))
                                                    .clientKey(Fn.base64decode(cfg.getClientKey()))
                                                    .build();
        }
        return k8sProvider;
    }

    @Override
    protected String getBase64CertData()  { return aksCluster.getKubeConfig().get(0).getClusterCaCertificate(); }

    @Override
    protected String getClusterEndpoint() { return aksCluster.getKubeConfig().get(0).getHost(); }

    @Override
    protected String getToken()           { throw new UnsupportedOperationException("Use configureK8s()"); }

    // -------------------------------------------------------------------------
    // CloudProvider implementation
    // -------------------------------------------------------------------------

    @Override
    public List<ITerraformDependable> getDependables() {
        return List.of(aksCluster);
    }

    @Override
    public void configureLoadBalancerDestroy(List<Service> loadBalancers) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    // -------------------------------------------------------------------------
    // Ingress controller + TLS
    // -------------------------------------------------------------------------

    private synchronized HelmProvider configureHelm() {
        if (helmProvider == null) {
            var cfg = aksCluster.getKubeConfig().get(0);
            helmProvider = HelmProvider.Builder.create(this, "helm")
                    .kubernetes(HelmProviderKubernetes.builder()
                            .host(cfg.getHost())
                            .clusterCaCertificate(Fn.base64decode(cfg.getClusterCaCertificate()))
                            .clientCertificate(Fn.base64decode(cfg.getClientCertificate()))
                            .clientKey(Fn.base64decode(cfg.getClientKey()))
                            .build())
                    .build();
        }
        return helmProvider;
    }

    public Release installNginxIngress() {
        return Release.Builder.create(this, "nginx-ingress")
                .provider(configureHelm())
                .dependsOn(List.of(aksCluster))
                .name("nginx-ingress")
                .chart("ingress-nginx")
                .repository("https://kubernetes.github.io/ingress-nginx")
                .namespace("ingress-nginx")
                .createNamespace(true)
                .timeout(600)
                .atomic(true)
                .set(List.of(ReleaseSet.builder()
                        .name("controller.service.annotations.service\\.beta\\.kubernetes\\.io/azure-load-balancer-health-probe-request-path")
                        .value("/healthz")
                        .build()))
                .build();
    }

    /**
     * Installs cert-manager and creates a Let's Encrypt ClusterIssuer.
     * The ClusterIssuer is applied via a local_exec provisioner rather than
     * kubernetes_manifest, which would contact the API server during plan
     * (before cert-manager's CRDs are registered) and fail with
     * "Failed to construct REST client".
     */
    public Release installCertManager() {
        String clusterIssuerYaml = format("""
                kubectl apply -f - <<'YAML'
                apiVersion: cert-manager.io/v1
                kind: ClusterIssuer
                metadata:
                  name: letsencrypt-prod
                spec:
                  acme:
                    server: https://acme-v02.api.letsencrypt.org/directory
                    email: %s
                    privateKeySecretRef:
                      name: letsencrypt-prod
                    solvers:
                    - http01:
                        ingress:
                          class: nginx
                YAML""", ACME_EMAIL);

        return Release.Builder.create(this, "cert-manager")
                .provider(configureHelm())
                .dependsOn(List.of(aksCluster))
                .name("cert-manager")
                .chart("cert-manager")
                .repository("https://charts.jetstack.io")
                .namespace("cert-manager")
                .createNamespace(true)
                .timeout(300)
                .atomic(true)
                .set(List.of(ReleaseSet.builder().name("crds.enabled").value("true").build()))
                .provisioners(CdkUtils.uponCreateRun(clusterIssuerYaml))
                .build();
    }
}
