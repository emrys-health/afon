package health.emrys.afon.cdk.gcp;

import com.hashicorp.cdktf.Fn;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.TerraformResourceLifecycle;
import com.hashicorp.cdktf.TerraformOutput;
import com.hashicorp.cdktf.providers.google.compute_network.ComputeNetwork;
import com.hashicorp.cdktf.providers.google.compute_network.ComputeNetworkConfig;
import com.hashicorp.cdktf.providers.google.compute_router.ComputeRouter;
import com.hashicorp.cdktf.providers.google.compute_router.ComputeRouterConfig;
import com.hashicorp.cdktf.providers.google.compute_router_nat.ComputeRouterNat;
import com.hashicorp.cdktf.providers.google.compute_router_nat.ComputeRouterNatConfig;
import com.hashicorp.cdktf.providers.google.compute_subnetwork.ComputeSubnetwork;
import com.hashicorp.cdktf.providers.google.compute_subnetwork.ComputeSubnetworkConfig;
import com.hashicorp.cdktf.providers.google.compute_subnetwork.ComputeSubnetworkSecondaryIpRange;
import com.hashicorp.cdktf.providers.google.container_cluster.*;
import com.hashicorp.cdktf.providers.google.data_google_compute_addresses.DataGoogleComputeAddresses;
import com.hashicorp.cdktf.providers.google.data_google_dns_managed_zone.DataGoogleDnsManagedZone;
import com.hashicorp.cdktf.providers.google.data_google_dns_managed_zone.DataGoogleDnsManagedZoneConfig;
import com.hashicorp.cdktf.providers.google.dns_record_set.DnsRecordSet;
import com.hashicorp.cdktf.providers.google.dns_record_set.DnsRecordSetConfig;
import com.hashicorp.cdktf.providers.google.provider.GoogleProvider;
import com.hashicorp.cdktf.providers.google.provider.GoogleProviderConfig;
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
import health.emrys.afon.cdk.Whitelister;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonCloudProperty;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.StackId;
import org.slf4j.Logger;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static health.emrys.afon.config.AfonCloud.GCP;
import static health.emrys.afon.config.AfonProperty.GCP_SECRETS_ENABLED;
import static java.lang.String.format;

/**
 * Authentication: the Google provider uses Application Default Credentials (ADC), resolved when it runs and
 * renewed by the Google auth library, as the AWS and Azure providers resolve the credentials of the AWS SDK
 * and of {@code az login}.  No token is written into the plan, so it does not expire after an hour.
 * Locally, log in once with {@code gcloud auth login --update-adc} (as {@code gcp-login} does), or
 * point {@code GOOGLE_APPLICATION_CREDENTIALS} at a credentials file, eg one from Workload Identity Federation in CI.
 * <p>
 * The Kubernetes and Helm providers use gke-gcloud-auth-plugin, which fetches
 * tokens on demand.
 * Install with: gcloud components install gke-gcloud-auth-plugin
 * <p>
 * If you need to destroy by hand, run:
 * gcloud container clusters delete $CLUSTER_NAME --region=$REGION --project $GCP_PROJECT_ID
 */
public class GcpCloudProvider extends AbstractHttpsCloudProvider {

    private static final Logger  logger             = org.slf4j.LoggerFactory.getLogger(GcpCloudProvider.class);
    private static final Pattern DNS_ZONE_NAME_RULE = Pattern.compile("[a-z]([-a-z0-9]*[a-z0-9])?");
    private static final int     DNS_ZONE_NAME_MAX  = 63;
    /** The nodes' subnet, from which a pod's call to the ingress' public address can arrive, if it is translated on the node. */
    public static final String NODE_CIDR = "10.10.0.0/24";
    /** The secondary range of the subnet that the pods get their addresses from. */
    public static final String POD_CIDR  = "10.20.0.0/16";

    private final ContainerCluster   gkeCluster;
    private final String             projectId;
    private final String             clusterName;
    private final boolean            secretsEnabled;
    private final String             egressIp;
    private final AfonPropertyProvider properties;
    private       KubernetesProvider k8sProvider;
    private       HelmProvider       helmProvider;

    /**
     * Looks up, rather than manages, the static IP of {@code GCP_EGRESS_IP}, so that a destroy cannot release an address that
     * is still on somebody's allow-list.  The Cloud NAT is given the link of the static IP, which is found among those of the
     * project and region by its address, so the plan fails if there is none.
     *
     * @return the link of the static IP, resolved when the plan is applied
     */
    private String lookupEgressIp(String egressIp, String gcpRegion) {
        DataGoogleComputeAddresses addresses = DataGoogleComputeAddresses.Builder
            .create(this, "egress-ips")
            .project(projectId)
            .region(gcpRegion)
            .filter(format("address = \"%s\"", egressIp))
            .build();
        return addresses.getAddresses().get(0).getSelfLink();
    }

    public static GcpCloudProvider bootstrap(TerraformStack stack, StackId stackId, AfonPropertyProvider properties,
                                             boolean pinnedEgressIp) {
        NullProvider.Builder.create(stack, "null").build();
        return new GcpCloudProvider(stack, stackId.cloud().toString(), stackId, properties, pinnedEgressIp);
    }

    /**
     * @return the cluster name of the stack, ie {@code <stack-type>-<clusterName>}, as for AWS and Azure, so that stacks of
     * different types run side by side in the same project
     */
    static String clusterName(String clusterName, StackId stackId) {
        return stackId.type() + "-" + clusterName;
    }

    /**
     * @param pinnedEgressIp whether the egress IP of the stack is pinned to {@code GCP_EGRESS_IP}, if set, which only one
     *                       running stack can hold, else Google allocates the addresses of its Cloud NAT
     */
    GcpCloudProvider(final Construct scope, final String id, StackId stackId, AfonPropertyProvider properties,
                     boolean pinnedEgressIp) {
        super(scope, id);
        this.properties = properties;
        this.projectId = projectIdFrom(properties);
        this.secretsEnabled = Boolean.parseBoolean(properties.getValue(GCP_SECRETS_ENABLED, "false"));
        this.clusterName = clusterName(properties.getValue(AfonProperty.CLUSTER_NAME), stackId);
        String gcpRegion = properties.getValue(AfonProperty.GCP_REGION);
        String vpcName     = "gke-private-vpc-" + clusterName;
        String subnetName  = "gke-private-subnet-" + clusterName;

        // Credentials come from Application Default Credentials at run time.  User credentials need a project
        // to bill API usage to, which is the stack's own, rather than the one of the Google client.
        new GoogleProvider(
            this, "google", GoogleProviderConfig
            .builder()
            .project(projectId)
            .region(gcpRegion).zone("europe-west2-a")
            .userProjectOverride(true)
            .billingProject(projectId)
            .build());

        // 2. Create the GKE Cluster
        ComputeNetwork vpc = new ComputeNetwork(
            this, "vpc", ComputeNetworkConfig
            .builder()
            .name(vpcName)
            .autoCreateSubnetworks(false)
            .build());

        ComputeSubnetworkConfig subnetworkConfig = ComputeSubnetworkConfig
            .builder()
            .name(subnetName)
            .ipCidrRange(NODE_CIDR)
            .region(gcpRegion)
            .network(vpc.getName())
            // Enable IP Aliasing/VPC-native networking
            .privateIpGoogleAccess(true)
            .secondaryIpRange(List.of(
                ComputeSubnetworkSecondaryIpRange
                    .builder()
                    .rangeName("pods")
                    .ipCidrRange(POD_CIDR)
                    .build(),
                ComputeSubnetworkSecondaryIpRange
                    .builder()
                    .rangeName("services")
                    .ipCidrRange("10.30.0.0/20")
                    .build()
                                     ))
            // Once the VPC-native GKE cluster attaches to these secondary ranges, GKE manages
            // them and a later plan sees a diff on secondary_ip_range. With the two-phase apply
            // (cluster bootstrapped first, full apply second) that diff becomes an in-place
            // subnet update, which GCP rejects while node instances occupy the subnet
            // ("subnetwork ... is already being used by ... instances/..."). The ranges are
            // static here, so ignore post-creation changes to them.
            .lifecycle(TerraformResourceLifecycle
                           .builder()
                           .ignoreChanges(List.of("secondary_ip_range"))
                           .build())
            .build();
        ComputeSubnetwork subnet = new ComputeSubnetwork(this, "subnet", subnetworkConfig);

        // Cloud NAT: gives private nodes outbound internet access so they can
        // pull images from external registries (registry.k8s.io, Docker Hub, etc.)
        ComputeRouter router = new ComputeRouter(
            this, "router", ComputeRouterConfig
            .builder()
            .name("gke-router-" + clusterName)
            .region(gcpRegion)
            .network(vpc.getName())
            .build());
        ComputeRouterNatConfig.Builder natConfig = ComputeRouterNatConfig
            .builder()
            .name("gke-nat")
            .router(router.getName())
            .region(gcpRegion)
            .sourceSubnetworkIpRangesToNat("ALL_SUBNETWORKS_ALL_IP_RANGES");
        egressIp = pinnedEgressIp ? properties.getCloudValue(GCP, AfonCloudProperty.EGRESS_IP, "").trim() : "";
        if (egressIp.isEmpty()) {
            natConfig.natIpAllocateOption("AUTO_ONLY");
        } else {
            // Everything the cluster sends to the internet leaves from one address a third party can put on an allow-list.
            // GCP_EGRESS_IP pins that address to a static IP created beforehand, which is what makes it survive a
            // destroy/apply cycle, unlike the ones that Google allocates when it is unset.
            natConfig.natIpAllocateOption("MANUAL_ONLY").natIps(List.of(lookupEgressIp(egressIp, gcpRegion)));
            // Reported so the address can be handed to whoever maintains the allow-list
            TerraformOutput.Builder
                .create(this, "egressIp")
                .value(egressIp)
                .description("Public IP all outbound cluster traffic is seen to come from")
                .build();
        }
        new ComputeRouterNat(this, "nat", natConfig.build());

        String gcloudCommand = format(
            "gcloud container clusters get-credentials %s --region %s --project %s",
            clusterName, gcpRegion, projectId);
        // This refreshes your local K8s config
        LocalExecProvisioner refreshCredentials = LocalExecProvisioner
            .builder()
            .command(gcloudCommand)
            .interpreter(List.of("/bin/bash", "-c"))
            .type("local-exec")
            .build();
        ContainerClusterConfig clusterConfig = ContainerClusterConfig
            .builder()
            .name(clusterName)
            .location(gcpRegion)
            .network(vpc.getName())
            .subnetwork(subnet.getName())
            .networkingMode("VPC_NATIVE")
            .initialNodeCount(1)
            .nodeLocations(List.of("europe-west2-a", "europe-west2-b"))
            .removeDefaultNodePool(false)
            .timeouts(ContainerClusterTimeouts.builder().create("20m").build())
            .privateClusterConfig(ContainerClusterPrivateClusterConfig
                                      .builder()
                                      .enablePrivateEndpoint(false) // Allows admin access via public endpoint
                                      .enablePrivateNodes(true)     // All node pools are private by default
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
            .build();
        gkeCluster = new ContainerCluster(this, "gkeCluster", clusterConfig);

        // Output the Cluster Endpoint
        com.hashicorp.cdktf.TerraformOutput.Builder.create(this, "gkeEndpoint")
                                                   .value(gkeCluster.getEndpoint())
                                                   .build();
        // not left to whichever component first needs it, as a stack of the KeyCloak alone creates kubernetes resources
        // without any other asking for the provider
        configureK8s();
    }

    /**
     * @return the project the stack is deployed into, as configured by {@link AfonProperty#GCP_PROJECT_ID}.
     * Find the available ones with: {@code gcloud projects list --format="table(projectId,name)"}
     * @throws IllegalArgumentException if it is not set
     */
    private static String projectIdFrom(AfonPropertyProvider properties) {
        String projectId = properties.getValue(AfonProperty.GCP_PROJECT_ID, "").trim();
        if (projectId.isEmpty()) {
            throw new IllegalArgumentException(
                "Property GCP_PROJECT_ID is required by the GCP stacks, but is not set: set it in afon-gcp.properties");
        }
        return projectId;
    }

    // -------------------------------------------------------------------------
    // AbstractCloudProvider implementation
    // -------------------------------------------------------------------------

    @Override
    public List<ITerraformDependable> getDependables() {
        return List.of(gkeCluster);
    }

    @Override
    public String getBase64CertData() {
        return gkeCluster.getMasterAuth().getClusterCaCertificate();
    }

    @Override
    public String getClusterEndpoint() {
        return "https://" + gkeCluster.getEndpoint();
    }

    /**
     * Uses gke-gcloud-auth-plugin so that credentials are fetched on demand during apply.
     */
    /**
     * GKE adds {@code cloud.google.com/neg: {"ingress":true}} to the Services it can serve with network endpoint
     * groups, which only the GKE ingress needs.  The ingress here is nginx, which reaches the pods through its own
     * Service, so opt out: GKE does not add the annotation if it is already declared.
     */
    @Override
    public Map<String, String> getServiceAnnotations() {
        return Map.of("cloud.google.com/neg", "{\"ingress\": false}");
    }

    @Override
    public synchronized KubernetesProvider configureK8s() {
        if (k8sProvider == null) {
            k8sProvider = KubernetesProvider.Builder
                .create(this, "kubernetes")
                .host(getClusterEndpoint())
                .clusterCaCertificate(Fn.base64decode(gkeCluster.getMasterAuth().getClusterCaCertificate()))
                .exec(List.of(KubernetesProviderExec
                                  .builder()
                                  .apiVersion("client.authentication.k8s.io/v1beta1")
                                  .command("gke-gcloud-auth-plugin")
                                  .build()))
                .build();
        }
        return k8sProvider;
    }

    // -------------------------------------------------------------------------
    // Ingress controller + TLS (mirroring AzureCloudProvider)
    // -------------------------------------------------------------------------

    public synchronized HelmProvider configureHelm() {
        if (helmProvider == null) {
            HelmProviderKubernetesExec helmPlugin = HelmProviderKubernetesExec
                .builder()
                .apiVersion("client.authentication.k8s.io/v1beta1")
                .command("gke-gcloud-auth-plugin")
                .build();
            HelmProviderKubernetes kubernetes = HelmProviderKubernetes
                .builder()
                .host(getClusterEndpoint())
                .clusterCaCertificate(
                    Fn.base64decode(
                        gkeCluster.getMasterAuth().getClusterCaCertificate()))
                .exec(helmPlugin)
                .build();
            helmProvider = HelmProvider.Builder.create(this, "helm")
                                               .kubernetes(kubernetes)
                                               .build();
        }
        return helmProvider;
    }


    @Override
    public AfonCloud cloud() {
        return GCP;
    }

    @Override
    public String clusterName() {
        return clusterName;
    }

    @Override
    protected Map<String, String> nginxAnnotations() {
        if (allowedCidrs("polaris").isEmpty()) {
            return null;
        }
        // else the load balancer hides the callers behind a node's IP, and the ingress cannot tell them apart
        return Map.of("controller.service.externalTrafficPolicy", "Local");
    }

    /**
     * The source CIDRs admitted to the route's component, from {@code GCP_<COMPONENT>_ALLOWED_CIDRS}.  Empty, ie open to all,
     * if not set.
     */
    @Override
    protected List<String> inboundCidrs(HttpsRoute route) {
        return withClusterCidrs(allowedCidrs(route.component()), egressIp);
    }

    /**
     * The configured CIDRs plus the cluster's own addresses, as its pods that call Polaris by its public domain name reach the
     * ingress controller from their own IPs, or from the node that translates the load balancer's address, or from the cluster's
     * egress IP: the pod range, the node subnet, and the pinned egress IP, if any.  Empty (no restriction) when nothing is
     * configured.
     *
     * @param egressIp the pinned egress IP, or empty if the cluster has none
     */
    static List<String> withClusterCidrs(List<String> cidrs, String egressIp) {
        if (cidrs.isEmpty()) {
            return cidrs;
        }
        List<String> all = new ArrayList<>(cidrs);
        for (String own : egressIp.isEmpty() ? List.of(POD_CIDR, NODE_CIDR) : List.of(egressIp + "/32", POD_CIDR, NODE_CIDR)) {
            if (!all.contains(own)) {
                all.add(own);
            }
        }
        return all;
    }

    private List<String> allowedCidrs(String component) {
        // only Polaris has an allow-list; the other components are open to all
        return component.equals("polaris")
               ? new Whitelister(properties).polarisWhitelistCidr(GCP)
               : List.of();
    }

    @Override
    protected Optional<String> restoreCachedCertCommand(HttpsRoute route) {
        return secretsEnabled
            ? Optional.of(restoreCachedCertCommand(projectId, tlsSecretNames(route.domainName()), route.tlsSecretName()))
            : Optional.empty();
    }

    static String[] tlsSecretNames(String domainName) {
        return CachedTlsSecret.secretNames(domainName);
    }

    /**
     * Restores the TLS secrets from Secret Manager before cert-manager's ingress-shim gets a
     * chance to request a fresh cert from Let's Encrypt.  Attached as an extra create-time command
     * on the cert-manager Helm release, so it runs (and completes) before the dependent Ingress is
     * even created.  The cache is populated externally, ahead of deploy, by
     * {@code scripts/gcp/issue-cert.sh} (or {@code scripts/gcp/secrets.sh store}).  A missing or empty
     * secret is not an error, cert-manager simply issues a new cert via its HTTP-01 ClusterIssuer as a fallback.
     */
    static String restoreCachedCertCommand(String projectId, String[] secretNames, String tlsSecretName) {
        logger.info("Restoring cached secrets {} from Secret Manager of project {}", secretNames, projectId);
        return CachedTlsSecret.restoreCommand(
            format("gcloud secrets versions access latest --secret='%s' --project='%s' 2>/dev/null", secretNames[0], projectId),
            format("gcloud secrets versions access latest --secret='%s' --project='%s' 2>/dev/null", secretNames[1], projectId),
            tlsSecretName);
    }

    /**
     * @return the name of the Cloud DNS managed zone of the domain name: the domain name with its dots replaced by
     * dashes, eg {@code emryspolarisgcp2-click} for {@code emryspolarisgcp2.click}.  An Azure DNS zone is named after
     * its domain name, but the name of a Cloud DNS zone cannot hold dots.  {@code scripts/gcp/create.sh} creates the
     * zone with the same name.
     * @throws IllegalArgumentException if the name is not a valid zone name, ie 1 to 63 lowercase letters, digits or
     * dashes, starting with a letter and not ending with a dash
     */
    static String dnsZoneName(String domainName) {
        String name = domainName.replace('.', '-');
        if (name.length() > DNS_ZONE_NAME_MAX || !DNS_ZONE_NAME_RULE.matcher(name).matches()) {
            throw new IllegalArgumentException(String.format(
                "The Cloud DNS zone name '%s', derived from the domain name '%s' by replacing its dots with dashes, "
                + "must be 1 to %d lowercase letters, digits or dashes, starting with a letter and not ending with a dash",
                name, domainName, DNS_ZONE_NAME_MAX));
        }
        return name;
    }

    @Override
    protected ITerraformDependable configureDns(IngressV1 ingress, HttpsRoute route) {
        // the ids of the first component exposed, Polaris, are in the tofu state of the deployed stacks
        boolean polaris = route.component().equals("polaris");
        DataGoogleDnsManagedZone zone = new DataGoogleDnsManagedZone(
            stack(),
            polaris ? "dns-zone" : "dns-zone-" + route.component(),
            DataGoogleDnsManagedZoneConfig
                .builder()
                .name(dnsZoneName(route.domainName()))
                .build());

        String ingressIp = ingress.getStatus().get(0).getLoadBalancer().get(0).getIngress().get(0).getIp();
        return new DnsRecordSet(
            stack(),
            route.component() + "-dns",
            DnsRecordSetConfig
                .builder()
                .name(route.domainName() + ".")
                .type("A")
                .ttl(300)
                .managedZone(zone.getName())
                .rrdatas(List.of(ingressIp))
                .build());
    }
}
