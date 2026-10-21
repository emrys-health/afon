package health.emrys.afon.cdk.azure;

import com.hashicorp.cdktf.Aspects;
import com.hashicorp.cdktf.Fn;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.IAspect;
import com.hashicorp.cdktf.TerraformResource;
import com.hashicorp.cdktf.TerraformIterator;
import com.hashicorp.cdktf.TerraformOutput;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.Token;
import com.hashicorp.cdktf.providers.azurerm.data_azurerm_client_config.DataAzurermClientConfig;
import com.hashicorp.cdktf.providers.azurerm.data_azurerm_dns_zone.DataAzurermDnsZone;
import com.hashicorp.cdktf.providers.azurerm.dns_a_record.DnsARecord;
import com.hashicorp.cdktf.providers.azurerm.dns_a_record.DnsARecordConfig;
import com.hashicorp.cdktf.providers.azurerm.data_azurerm_public_ips.DataAzurermPublicIps;
import com.hashicorp.cdktf.providers.azurerm.data_azurerm_resource_group.DataAzurermResourceGroup;
import com.hashicorp.cdktf.providers.azurerm.data_azurerm_resource_group.DataAzurermResourceGroupConfig;
import com.hashicorp.cdktf.providers.azurerm.kubernetes_cluster.*;
import com.hashicorp.cdktf.providers.azurerm.network_security_group.NetworkSecurityGroup;
import com.hashicorp.cdktf.providers.azurerm.network_security_group.NetworkSecurityGroupConfig;
import com.hashicorp.cdktf.providers.azurerm.network_security_group.NetworkSecurityGroupSecurityRule;
import com.hashicorp.cdktf.providers.azurerm.provider.AzurermProvider;
import com.hashicorp.cdktf.providers.azurerm.provider.AzurermProviderFeatures;
import com.hashicorp.cdktf.providers.azurerm.role_assignment.RoleAssignment;
import com.hashicorp.cdktf.providers.azurerm.role_assignment.RoleAssignmentConfig;
import com.hashicorp.cdktf.providers.azurerm.subnet.Subnet;
import com.hashicorp.cdktf.providers.azurerm.subnet.SubnetConfig;
import com.hashicorp.cdktf.providers.azurerm.subnet_network_security_group_association.SubnetNetworkSecurityGroupAssociation;
import com.hashicorp.cdktf.providers.azurerm.subnet_network_security_group_association.SubnetNetworkSecurityGroupAssociationConfig;
import com.hashicorp.cdktf.providers.azurerm.user_assigned_identity.UserAssignedIdentity;
import com.hashicorp.cdktf.providers.azurerm.user_assigned_identity.UserAssignedIdentityConfig;
import com.hashicorp.cdktf.providers.azurerm.virtual_network.VirtualNetwork;
import com.hashicorp.cdktf.providers.azurerm.virtual_network.VirtualNetworkConfig;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.provider.HelmProviderKubernetes;
import com.hashicorp.cdktf.providers.helm.provider.HelmProviderKubernetesExec;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProvider;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProviderExec;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import health.emrys.afon.cdk.AbstractHttpsCloudProvider;
import health.emrys.afon.cdk.CachedTlsSecret;
import health.emrys.afon.cdk.HttpsRoute;
import health.emrys.afon.cdk.LogStorage;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonCloudProperty;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.StackId;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import software.constructs.Construct;
import software.constructs.IConstruct;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static health.emrys.afon.config.AfonCloud.AZURE;
import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.config.AfonProperty.AZURE_RESOURCE_PROVIDER_REGISTRATIONS;
import static java.lang.String.format;

public class AzureCloudProvider extends AbstractHttpsCloudProvider {

    private static final Logger                  logger             = org.slf4j.LoggerFactory.getLogger(AzureCloudProvider.class);
    private static final String                  KUBERNETES_VERSION = "1.36.2";
    /**
     * Well-known AKS AAD server application ID; kubelogin requests a token for this audience.
     */
    private static final String                  AKS_AAD_SERVER_ID  = "6dae42f8-4368-4678-94ff-3960e28e3630";
    private static final String                  VNET_CIDR          = "10.1.0.0/16";
    private static final String                  SUBNET_CIDR        = "10.1.1.0/24";
    private static final String                  SERVICE_CIDR       = "10.2.0.0/16";
    private static final String                  DNS_SERVICE_IP     = "10.2.0.10";
    private final        String                  resourceGroup;
    private final        String                  subscriptionId;
    private final        KubernetesCluster       aksCluster;
    private final        String                  clusterName;
    private final        String                  stackType;
    private final        DataAzurermClientConfig clientConfig;
    private final        String                  providerRegistrations;
    private final        Map<String, String>     nodePoolConfig;
    private final        String                  keyVaultName;
    /** The public IP address that the outbound traffic of the cluster egresses from, if pinned, else empty. */
    private final        String                  egressIp;
    /** The pod CIDR of an AKS overlay cluster, as left unset in its network profile. */
    public static final String POD_CIDR = "10.244.0.0/16";

    private final        AfonPropertyProvider    properties;
    private              RoleAssignment          clusterAdminAssignment;
    private              KubernetesProvider      k8sProvider;
    private              HelmProvider            helmProvider;

    /**
     * @param pinnedEgressIp whether the egress IP of the stack is pinned to {@code AZURE_EGRESS_IP}, if set, which only
     *                       one running stack can hold, else Azure allocates one
     */
    public AzureCloudProvider(@NonNull Construct scope,
                              @NonNull String id,
                              @NonNull StackId stackId,
                              AfonPropertyProvider properties,
                              boolean pinnedEgressIp) {
        super(scope, id);
        this.properties          = properties;
        stackType                = stackId.type();
        clusterName              = clusterName(properties.getValue(AfonProperty.CLUSTER_NAME), stackId);
        resourceGroup            = properties.getValue(AZURE_RESOURCE_GROUP);
        subscriptionId           = properties.getValue(AZURE_SUBSCRIPTION);
        providerRegistrations    = properties.getValue(AZURE_RESOURCE_PROVIDER_REGISTRATIONS, "core");
        nodePoolConfig           = parseNodePoolConfig(properties.getValue(AZURE_AKS_NODE_POOL, ""));
        keyVaultName             = properties.getValue(AZURE_KEY_VAULT_NAME, "");
        egressIp                 = pinnedEgressIp ? properties.getCloudValue(AZURE, AfonCloudProperty.EGRESS_IP, "").trim() : "";
        configureProviders();
        clientConfig = new DataAzurermClientConfig(this, "current");
        aksCluster   = createCluster(lookupResourceGroup());
        grantClusterAdmin();
        Aspects.of(scope).add(new ClusterAdminAspect(clusterAdminAssignment));
        // not left to whichever component first needs it, as a stack of the KeyCloak alone creates kubernetes resources
        // without any other asking for the provider
        configureK8s();
    }

    public static AzureCloudProvider bootstrap(TerraformStack stack, StackId stackId, AfonPropertyProvider properties,
                                               boolean pinnedEgressIp) {
        return new AzureCloudProvider(stack, stackId.cloud().toString(), stackId, properties, pinnedEgressIp);
    }

    /**
     * @return the cluster name of the stack, ie {@code <stack-type>-<clusterName>}, as for AWS, so that stacks of different
     * types run side by side in the same resource group
     */
    static String clusterName(String clusterName, StackId stackId) {
        return stackId.type() + "-" + clusterName;
    }

    /**
     * @return the name of a resource of the stack's network or identity, qualified by the stack type, as these are
     * named in the resource group, which the stacks running side by side share
     */
    private String resourceName(String name) {
        return stackType + "-" + name;
    }

    /**
     * Parses the {@code key1:value1;...;keyN:valueN} encoding used by {@link AfonProperty#AZURE_AKS_NODE_POOL}
     * into a {@code Map} keyed on the camel-cased name of the node pool builder method, eg {@code nodeCount} or {@code vmSize}.
     * If a key is missing, the value will fall back to its own default upon {@code Map#getOrDefault()}. .
     */
    private static Map<String, String> parseNodePoolConfig(String encoded) {
        Map<String, String> config = new HashMap<>();
        if (encoded == null || encoded.isBlank()) {
            return config;
        }
        for (String entry : encoded.split(";")) {
            String[] keyValue = entry.split(":", 2);
            if (keyValue.length == 2) {
                config.put(keyValue[0].trim(), keyValue[1].trim());
            }
        }
        return config;
    }

    /**
     * Create cluster for existing {@link DataAzurermResourceGroup}
     *
     * @param resourceGroup
     * @return A KubernetesCluster
     */
    private @NonNull KubernetesCluster createCluster(DataAzurermResourceGroup resourceGroup) {
        UserAssignedIdentity identity   = createIdentity(resourceGroup);
        Subnet               nodeSubnet = createNetworking(resourceGroup, identity);
        return createCluster(resourceGroup, nodeSubnet, identity);
    }

    /**
     * With the local admin account disabled, authentication is AAD-only, so the identity
     * running {@code tofu} must be granted an Azure RBAC role on the cluster — otherwise
     * apply/destroy of in-cluster resources fails with Unauthorized.
     *
     * <p>An Azure RBAC assignment returns immediately from ARM but takes some minutes to
     * propagate to the AKS authorization webhook, so a same-run apply of in-cluster
     * resources can still be Forbidden. The create-time provisioner blocks until
     * {@code kubectl auth can-i} confirms the grant is effective; because the in-cluster
     * resources depend on this assignment, they will not start until it does.
     */
    private void grantClusterAdmin() {
        LocalExecProvisioner waitForRbac = LocalExecProvisioner
            .builder()
            .when("create")
            .type("local-exec")
            .command(format(
                "az aks get-credentials --resource-group %s --name %s --overwrite-existing && " +
                    "for i in $(seq 1 30); do " +
                    "kubelogin convert-kubeconfig -l azurecli >/dev/null 2>&1; " +
                    "kubectl auth can-i create deployments -n default >/dev/null 2>&1 && exit 0; " +
                    "sleep 10; " +
                    "done; " +
                    "echo 'Cluster Admin RBAC did not propagate within 5 minutes' >&2; exit 1",
                resourceGroup, clusterName))
            .build();

        clusterAdminAssignment = new RoleAssignment(
            this, "tofu-cluster-admin", RoleAssignmentConfig
            .builder()
            .principalId(clientConfig.getObjectId())
            .principalType("User")
            .roleDefinitionName("Azure Kubernetes Service RBAC Cluster Admin")
            .scope(aksCluster.getId())
            .provisioners(List.of(waitForRbac))
            .build());
    }

    /**
     * Makes every resource acting on the cluster, ie that of the kubernetes and helm providers, and the commands running
     * kubectl, wait for the cluster admin assignment, wherever it is created, as else any that does not name the cluster's
     * dependables itself is created before the assignment has propagated and is refused ("User does not have access to the
     * resource in Azure"), and, on destroy, is deleted after it is revoked.
     */
    static class ClusterAdminAspect implements IAspect {

        private static final List<String> CLUSTER_RESOURCE_PREFIXES = List.of("kubernetes_", "helm_", "null_resource");

        private final RoleAssignment clusterAdmin;

        ClusterAdminAspect(RoleAssignment clusterAdmin) {
            this.clusterAdmin = clusterAdmin;
        }

        @Override
        public void visit(@NonNull IConstruct node) {
            if (!(node instanceof TerraformResource resource) || resource == clusterAdmin
                || CLUSTER_RESOURCE_PREFIXES.stream().noneMatch(resource.getTerraformResourceType()::startsWith)) {
                return;
            }
            // the address of the resource, as a depends_on takes it, not the reference to it
            String address = clusterAdmin.getTerraformResourceType() + "." + clusterAdmin.getFriendlyUniqueId();
            List<String> dependsOn = new ArrayList<>(resource.getDependsOn() == null ? List.of() : resource.getDependsOn());
            if (!dependsOn.contains(address)) {
                dependsOn.add(address);
                resource.setDependsOn(dependsOn);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Providers
    // -------------------------------------------------------------------------

    /**
     * The exact version of the azurerm provider, newer than the one the bindings were generated for (4.34.0): the bindings,
     * which are not published for later versions, only lack its newer arguments, eg {@code network_profile.advanced_networking}
     * of {@code azurerm_kubernetes_cluster} (from 4.45.0), which are set as overrides.
     */
    static final String AZURERM_VERSION = "4.81.0";

    private void configureProviders() {
        NullProvider.Builder.create(this, "null").build();
        AzurermProvider.Builder
            .create(this, "azurerm")
            .features(List.of(AzurermProviderFeatures.builder().build()))
            .subscriptionId(subscriptionId)
            .resourceProviderRegistrations(providerRegistrations)
            .build();
        TerraformStack.of(this).addOverride(
            "terraform.required_providers.azurerm", Map.of(
                "source", "hashicorp/azurerm",
                "version", AZURERM_VERSION));
    }

    // -------------------------------------------------------------------------
    // Pre-existing Resource group
    // -------------------------------------------------------------------------

    /**
     * The resource group is pre-existing and owned outside this stack, so it is looked up
     * rather than declared — declaring it would make tofu create it on apply (and delete it,
     * along with everything else in it, on destroy). Child resources take their location
     * from the group itself rather than from the region.
     */
    private DataAzurermResourceGroup lookupResourceGroup() {
        return new DataAzurermResourceGroup(
            this,
            "aks-rg",
            DataAzurermResourceGroupConfig
                .builder()
                .name(resourceGroup)
                .build());
    }

    // -------------------------------------------------------------------------
    // Managed identity (created before the cluster so we can pre-grant roles)
    // -------------------------------------------------------------------------

    private UserAssignedIdentity createIdentity(DataAzurermResourceGroup resourceGroup) {
        return new UserAssignedIdentity(
            this, "aks-identity", UserAssignedIdentityConfig
            .builder()
            .name(resourceName("aks-identity"))
            .location(resourceGroup.getLocation())
            .resourceGroupName(resourceGroup.getName())
            .build());
    }

    // -------------------------------------------------------------------------
    // Networking: VNet → Subnet → NSG → association → role assignment
    // -------------------------------------------------------------------------

    private Subnet createNetworking(DataAzurermResourceGroup resourceGroup, UserAssignedIdentity identity) {
        VirtualNetwork vnet = new VirtualNetwork(
            this,
            "aks-vnet",
            VirtualNetworkConfig
                .builder()
                .name(resourceName("aks-vnet"))
                .location(resourceGroup.getLocation())
                .resourceGroupName(resourceGroup.getName())
                .addressSpace(List.of(VNET_CIDR))
                .build());

        NetworkSecurityGroup nsg = createNsg(resourceGroup);

        Subnet subnet = new Subnet(
            this,
            "aks-subnet",
            SubnetConfig
                .builder()
                .name(resourceName("aks-subnet"))
                .resourceGroupName(resourceGroup.getName())
                .virtualNetworkName(vnet.getName())
                .addressPrefixes(List.of(SUBNET_CIDR))
                .build());

        new SubnetNetworkSecurityGroupAssociation(
            this, "aks-nsg-assoc",
            SubnetNetworkSecurityGroupAssociationConfig
                .builder()
                .subnetId(subnet.getId())
                .networkSecurityGroupId(nsg.getId())
                .build());

        new RoleAssignment(
            this, "aks-identity-network-contributor", RoleAssignmentConfig
            .builder()
            .principalId(identity.getPrincipalId())
            .roleDefinitionName("Network Contributor")
            .scope(subnet.getId())
            .build());

        return subnet;
    }

    private NetworkSecurityGroup createNsg(DataAzurermResourceGroup resourceGroup) {
        // Health probes from the Azure Load Balancer infrastructure
        NetworkSecurityGroupSecurityRule lbInbound = NetworkSecurityGroupSecurityRule
            .builder()
            .name(
                "AllowAzureLoadBalancerInbound")
            .priority(100)
            .direction("Inbound")
            .access("Allow")
            .protocol("*")
            .sourcePortRange("*")
            .destinationPortRange("*")
            .sourceAddressPrefix(
                "AzureLoadBalancer")
            .destinationAddressPrefix("*")
            .build();

        // The subnet NSG is evaluated post-DNAT (destination = node private IP : NodePort),
        // so we need the NodePort range here.
        // The NIC NSG is evaluated pre-DNAT (destination = LB frontend IP : 80/443),
        // which is why the AKS cloud controller creates NIC rules using the LB frontend IP —
        // those are handled automatically by AKS.
        NetworkSecurityGroupSecurityRule httpInbound = NetworkSecurityGroupSecurityRule
            .builder()
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

        NetworkSecurityGroupSecurityRule httpsInbound = NetworkSecurityGroupSecurityRule
            .builder()
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
        NetworkSecurityGroupSecurityRule nodePortInbound = NetworkSecurityGroupSecurityRule
            .builder()
            .name("AllowNodePortInbound")
            .priority(130)
            .direction("Inbound")
            .access("Allow")
            .protocol("Tcp")
            .sourcePortRange("*")
            .destinationPortRange(
                "30000-32767")
            .sourceAddressPrefix(
                "Internet")
            .destinationAddressPrefix(
                "*")
            .build();

        NetworkSecurityGroupSecurityRule internetOutbound = NetworkSecurityGroupSecurityRule
            .builder()
            .name(
                "AllowInternetOutbound")
            .priority(100)
            .direction("Outbound")
            .access("Allow")
            .protocol("*")
            .sourcePortRange("*")
            .destinationPortRange("*")
            .sourceAddressPrefix("*")
            .destinationAddressPrefix("Internet")
            .build();

        NetworkSecurityGroupSecurityRule outbound = NetworkSecurityGroupSecurityRule
            .builder()
            .name(
                "AllowAzureLoadBalancerOutbound")
            .priority(110)
            .direction("Outbound")
            .access("Allow")
            .protocol("*")
            .sourcePortRange("*")
            .destinationPortRange("*")
            .sourceAddressPrefix(
                "AzureLoadBalancer")
            .destinationAddressPrefix("*")
            .build();

        return new NetworkSecurityGroup(
            this, "aks-nsg", NetworkSecurityGroupConfig
            .builder()
            .name(resourceName("aks-nsg"))
            .location(resourceGroup.getLocation())
            .resourceGroupName(resourceGroup.getName())
            .securityRule(List.of(
                lbInbound, httpInbound, httpsInbound,
                nodePortInbound, internetOutbound,
                outbound))
            .build());
    }

    // -------------------------------------------------------------------------
    // AKS cluster
    // -------------------------------------------------------------------------

    private KubernetesCluster createCluster(DataAzurermResourceGroup resourceGroup,
                                            Subnet nodeSubnet,
                                            UserAssignedIdentity identity) {
        KubernetesClusterDefaultNodePool nodePool = KubernetesClusterDefaultNodePool
            .builder()
            .name("systempool")
            .nodeCount(Integer.parseInt(nodePoolConfig.getOrDefault("nodeCount", "3")))
            .vmSize(nodePoolConfig.getOrDefault("vmSize", "Standard_D2s_v3"))
            .vnetSubnetId(nodeSubnet.getId())
            // Declared explicitly to match Azure's own applied defaults; left unset,
            // this block is not computed and every plan diffs it away as removed.
            .upgradeSettings(KubernetesClusterDefaultNodePoolUpgradeSettings
                                 .builder()
                                 .maxSurge("10%")
                                 .drainTimeoutInMinutes(0)
                                 .nodeSoakDurationInMinutes(0)
                                 .build())
            .build();

        KubernetesClusterNetworkProfile.Builder networkProfileBuilder = KubernetesClusterNetworkProfile
            .builder()
            .networkPlugin("azure")
            .loadBalancerSku("standard")
            .serviceCidr(SERVICE_CIDR)
            .dnsServiceIp(DNS_SERVICE_IP)
            .networkPlugin("azure")
            .networkDataPlane("cilium")
            .networkPluginMode("overlay");
        List<RoleAssignment> egressIpAssignments = new ArrayList<>();
        if (!egressIp.isEmpty()) {
            // Everything the cluster sends to the internet leaves from one address a third party can put on an allow-list.
            // AZURE_EGRESS_IP pins that address to a public IP created beforehand, which is what makes it survive a
            // destroy/apply cycle, unlike the one Azure allocates when it is unset.
            String egressIpId = lookupEgressIp(resourceGroup);
            networkProfileBuilder.loadBalancerProfile(KubernetesClusterNetworkProfileLoadBalancerProfile
                                                          .builder()
                                                          .outboundIpAddressIds(List.of(egressIpId))
                                                          .build());
            // The cluster's identity must be allowed to join the public IP to its load balancer, which its role on the
            // node subnet does not cover.
            egressIpAssignments.add(new RoleAssignment(
                this, "aks-identity-egress-ip-network-contributor", RoleAssignmentConfig
                .builder()
                .principalId(identity.getPrincipalId())
                .roleDefinitionName("Network Contributor")
                .scope(egressIpId)
                .build()));
            // Reported so the address can be handed to whoever maintains the allow-list
            TerraformOutput.Builder
                .create(this, "egressIp")
                .value(egressIp)
                .description("Public IP all outbound cluster traffic is seen to come from")
                .build();
        }
        KubernetesClusterNetworkProfile networkProfile = networkProfileBuilder.build();

        LocalExecProvisioner updateCredentials = LocalExecProvisioner
            .builder()
            .when("create")
            .command(format(
                "az aks get-credentials --resource-group %s " +
                    "--name %s --overwrite-existing",
                resourceGroup.getName(), clusterName))
            .type("local-exec")
            .build();

        KubernetesCluster cluster = KubernetesCluster.Builder
            .create(this, "aks")
            .name(clusterName)
            .location(resourceGroup.getLocation())
            .resourceGroupName(resourceGroup.getName())
            .dnsPrefix(clusterName)
            .kubernetesVersion(KUBERNETES_VERSION)
            .defaultNodePool(nodePool)
            // Managed AAD + Azure RBAC. Local account is disabled so credentials are no
            // longer long-lived client certs baked into state; kubelogin mints a fresh
            // token per provider invocation (see configureK8s/configureHelm).
            .azureActiveDirectoryRoleBasedAccessControl(
                KubernetesClusterAzureActiveDirectoryRoleBasedAccessControl
                    .builder()
                    .tenantId(clientConfig.getTenantId())
                    .azureRbacEnabled(true)
                    .build())
            .roleBasedAccessControlEnabled(true)
            .localAccountDisabled(true)
            // The live cluster already has the OIDC issuer enabled; Azure refuses to
            // disable it once on (OIDCIssuerFeatureCannotBeDisabled), so declare it
            // enabled to avoid a spurious update diff.
            .oidcIssuerEnabled(true)
            .identity(KubernetesClusterIdentity
                          .builder()
                          .type("UserAssigned")
                          .identityIds(List.of(identity.getId()))
                          .build())
            .networkProfile(networkProfile)
            .dependsOn(egressIpAssignments)
            .timeouts(KubernetesClusterTimeouts
                          .builder()
                          .create("20m")
                          .build())
            .provisioners(List.of(updateCredentials))
            .build();
        // Advanced Container Networking Services, whose security feature gives Cilium the DNS proxy that the FQDN
        // network policies need (see NetworkConfig).  The typed network profile of the provider bindings has no such block,
        // but the azurerm version pinned (see AZURERM_VERSION) has, so it is set as an override of the synthesised one.
        cluster.addOverride("network_profile.advanced_networking", Map.of("security_enabled", true));
        return cluster;
    }

    /**
     * Looks up, rather than manages, the public IP of {@code AZURE_EGRESS_IP}, so that a destroy cannot release an address
     * that is still on somebody's allow-list.  Azure has no lookup by address, so the public IPs of the resource group are
     * listed, and the one with the address is picked: the plan fails if there is none.
     *
     * @return the id of the public IP, resolved when the plan is applied
     */
    private String lookupEgressIp(DataAzurermResourceGroup resourceGroup) {
        DataAzurermPublicIps publicIps = DataAzurermPublicIps.Builder
            .create(this, "egress-ips")
            .resourceGroupName(resourceGroup.getName())
            .build();
        TerraformIterator each = TerraformIterator.fromList(publicIps.getPublicIps());
        List<String> ids       = Token.asList(each.pluckProperty("id"));
        List<String> addresses = Token.asList(each.pluckProperty("ip_address"));
        return Token.asString(Fn.element(Fn.matchkeys(ids, addresses, List.of(egressIp)), 0));
    }

    // -------------------------------------------------------------------------
    // AbstractCloudProvider implementation
    // -------------------------------------------------------------------------

    /**
     * Uses kubelogin instead of static client certs so that credentials are minted fresh
     * on every provider invocation. This avoids the Unauthorized failures on destroy that
     * occur when the cluster's client certificate (baked into state at apply time) has
     * since rotated. Requires kubelogin on PATH ({@code az aks install-cli}).
     */
    @Override
    public synchronized KubernetesProvider configureK8s() {
        if (k8sProvider == null) {
            var cfg = aksCluster.getKubeConfig().get(0);
            k8sProvider = KubernetesProvider.Builder
                .create(this, "kubernetes")
                .host(cfg.getHost())
                .clusterCaCertificate(Fn.base64decode(cfg.getClusterCaCertificate()))
                .exec(List.of(KubernetesProviderExec
                                  .builder()
                                  .apiVersion("client.authentication.k8s.io/v1beta1")
                                  .command("kubelogin")
                                  .args(List.of("get-token", "--login", "azurecli",
                                                "--server-id", AKS_AAD_SERVER_ID))
                                  .build()))
                .build();
        }
        return k8sProvider;
    }

    @Override
    protected String getBase64CertData() {
        return aksCluster.getKubeConfig().get(0).getClusterCaCertificate();
    }

    @Override
    protected String getClusterEndpoint() {
        return aksCluster.getKubeConfig().get(0).getHost();
    }

    // -------------------------------------------------------------------------
    // CloudProvider implementation
    // -------------------------------------------------------------------------

    @Override
    public List<ITerraformDependable> getDependables() {
        // Include the RBAC assignment so in-cluster resources are created after the
        // tofu identity can authenticate, and (crucially) destroyed before it is revoked.
        return List.of(aksCluster, clusterAdminAssignment);
    }

    // -------------------------------------------------------------------------
    // Ingress controller + TLS
    // -------------------------------------------------------------------------

    public synchronized HelmProvider configureHelm() {
        if (helmProvider == null) {
            var cfg = aksCluster.getKubeConfig().get(0);
            helmProvider = HelmProvider.Builder.create(this, "helm")
                                               .kubernetes(HelmProviderKubernetes.builder()
                                                                                 .host(cfg.getHost())
                                                                                 .clusterCaCertificate(Fn.base64decode(cfg.getClusterCaCertificate()))
                                                                                 .exec(HelmProviderKubernetesExec
                                                                                           .builder()
                                                                                           .apiVersion("client.authentication.k8s.io/v1beta1")
                                                                                           .command("kubelogin")
                                                                                           .args(List.of("get-token", "--login", "azurecli",
                                                                                                         "--server-id", AKS_AAD_SERVER_ID))
                                                                                           .build())
                                                                                 .build())
                                               .build();
        }
        return helmProvider;
    }


    @Override
    public AfonCloud cloud() {
        return AZURE;
    }

    /**
     * The Azure CNI (Cilium) data plane is not necessarily ready the moment the azurerm_kubernetes_cluster resource
     * reports "created".  Gating the Spark operator on the nginx release, which has already scheduled pods
     * successfully, proves the cilium-agent is serving requests, avoiding the "Cilium API client timeout exceeded"
     * pod-sandbox failure.
     */
    @Override
    public List<? extends ITerraformDependable> sparkOperatorDependencies() {
        return List.of(nginxRelease());
    }

    @Override
    public String clusterName() {
        return clusterName;
    }

    /**
     * The logs are kept in the container of the "azure" catalog (see catalogs-azure.properties), in the storage
     * account {@code AZURE_STORAGE_ACCOUNT}, with the access of {@code AZURE_CONNECTION_STRING}.
     */
    @Override
    public LogStorage logStorage() {
        String container        = properties.getCatalogProperty("AZURE_BUCKET_NAME", "azure");
        String storageAccount   = properties.getValue(AZURE_STORAGE_ACCOUNT);
        String connectionString = properties.getValue(AZURE_CONNECTION_STRING);
        if (connectionString.isBlank()) {
            // Loki would otherwise only fail at startup, after the rest of the stack is deployed
            throw new IllegalArgumentException(
                "AZURE_CONNECTION_STRING is not set, which the monitoring needs for Loki to write its logs to the storage account");
        }
        return new LogStorage("Blob", "azure", format("""
            type: azure
            bucketNames:
              chunks: %1$s
              ruler: %1$s
              admin: %1$s
            azure:
              accountName: %2$s
              connectionString: "%3$s"
              useManagedIdentity: false
            """, container, storageAccount, connectionString));
    }

    @Override
    protected Map<String, String> nginxAnnotations() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("controller.service.annotations.service\\.beta\\" +
                   ".kubernetes\\.io/azure-load-balancer-health-probe" +
                   "-request-path", "/healthz");
        if (!allowedCidrs("polaris").isEmpty()) {
            // else the load balancer SNATs the callers to a node's IP, and the ingress cannot tell them apart
            values.put("controller.service.externalTrafficPolicy", "Local");
        }
        return values;
    }

    /**
     * The source CIDRs admitted to the route's component, from {@code AZURE_<COMPONENT>_ALLOWED_CIDRS}.  Empty, ie
     * open to all, if not set.
     */
    @Override
    protected List<String> inboundCidrs(HttpsRoute route) {
        return withClusterCidrs(allowedCidrs(route.component()), egressIp);
    }

    /**
     * The configured CIDRs plus the cluster's own addresses, as its pods that call Polaris by its public domain name reach the
     * ingress controller from their own IPs, Cilium translating the load balancer's address to the controller's pod, or from the
     * cluster's egress IP: the pod CIDR, and the pinned egress IP, if any.  Empty (no restriction) when nothing is configured.
     *
     * @param egressIp the pinned egress IP, or empty if the cluster has none
     */
    static List<String> withClusterCidrs(List<String> cidrs, String egressIp) {
        if (cidrs.isEmpty()) {
            return cidrs;
        }
        List<String> all = new ArrayList<>(cidrs);
        if (!egressIp.isEmpty() && !all.contains(egressIp + "/32")) {
            all.add(egressIp + "/32");
        }
        if (!all.contains(POD_CIDR)) {
            all.add(POD_CIDR);
        }
        return all;
    }

    private List<String> allowedCidrs(String component) {
        // only Polaris has an allow-list; the other components are open to all
        return component.equals("polaris")
               ? parseCidrs(properties.getCloudValue(AZURE, AfonCloudProperty.POLARIS_ALLOWED_CIDRS, ""))
               : List.of();
    }

    static List<String> parseCidrs(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).filter(cidr -> !cidr.isEmpty()).toList();
    }

    @Override
    protected Optional<String> restoreCachedCertCommand(HttpsRoute route) {
        return keyVaultName.isBlank()
            ? Optional.empty()
            : Optional.of(restoreCachedCertCommand(keyVaultName, tlsSecretNames(route.domainName()), route.tlsSecretName()));
    }

    static String[] tlsSecretNames(String domainName) {
        return CachedTlsSecret.secretNames(domainName);
    }

    /**
     * Restores the TLS secrets from Key Vault before cert-manager's ingress-shim gets a
     * chance to request a fresh cert from Let's Encrypt. Attached as an extra create-time command
     * on the cert-manager Helm release, so it runs (and completes) before the dependent Ingress is
     * even created. The cache is populated externally, ahead of deploy, by {@code scripts/azure/
     * issue-cert.sh <acme|digicert|globalsign>} — issuer-agnostic from here, it's just two named
     * secrets. A missing or empty Key Vault entry is not an error, cert-manager simply issues a new
     * cert via its HTTP-01 ClusterIssuer as a fallback.
     */
    static String restoreCachedCertCommand(String keyVaultName, String[] secretNames, String tlsSecretName) {
        logger.info("Restoring cached secrets {} from key vault {}", secretNames, keyVaultName);
        return CachedTlsSecret.restoreCommand(
            format("az keyvault secret show --vault-name '%s' --name '%s' --query value -o tsv 2>/dev/null", keyVaultName, secretNames[0]),
            format("az keyvault secret show --vault-name '%s' --name '%s' --query value -o tsv 2>/dev/null", keyVaultName, secretNames[1]),
            tlsSecretName);
    }

    @Override
    protected ITerraformDependable configureDns(IngressV1 ingress, HttpsRoute route) {
        String domainName = route.domainName();
        String suffix = domainName.replace(".", "-");
        DataAzurermDnsZone dnsZone = DataAzurermDnsZone.Builder
            .create(stack(), "dns-zone-" + suffix)
            .name(domainName)
            .resourceGroupName(resourceGroup)
            .build();

        List<String> ipAddresses = List.of(
            ingress.getStatus().get(0).getLoadBalancer().get(0).getIngress().get(0).getIp());
        return new DnsARecord(
            stack(), "dns-" + suffix,
            DnsARecordConfig
                .builder()
                .name("@")
                .zoneName(dnsZone.getName())
                .resourceGroupName(resourceGroup)
                .ttl(300)
                .records(ipAddresses)
                .build());
    }
}
