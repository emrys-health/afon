package health.emrys.afon.cdk.gcp;

import com.hashicorp.cdktf.Fn;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.TerraformResourceLifecycle;
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
import com.hashicorp.cdktf.providers.google.provider.GoogleProvider;
import com.hashicorp.cdktf.providers.google.provider.GoogleProviderConfig;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.provider.HelmProviderKubernetes;
import com.hashicorp.cdktf.providers.helm.provider.HelmProviderKubernetesExec;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProvider;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProviderExec;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import health.emrys.afon.cdk.AbstractCloudProvider;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import software.constructs.Construct;

import java.io.IOException;
import java.util.List;

import static java.lang.String.format;

/**
 * Authentication: runs {@code gcloud auth print-access-token} at synthesis time to
 * read a fresh OAuth2 token from {@code $HOME/.config/gcloud} without going through
 * the OAuth2 refresh flow (which triggers RAPT on Workspace accounts).
 * The token is valid for 1 hour, so synth and apply must complete within that window.
 * <p>
 * The Kubernetes and Helm providers use gke-gcloud-auth-plugin, which fetches
 * tokens on demand without going through the refresh flow.
 * Install with: gcloud components install gke-gcloud-auth-plugin
 * <p>
 * If you need to destroy by hand, run:
 * gcloud container clusters delete $CLUSTER_NAME --region=$REGION --project afon-core
 */
public class GcpCloudProvider extends AbstractCloudProvider {

    /**
     * Generate with:
     * gcloud projects list --format="table(projectId,name)"
     */
    private final String PROJECT_ID = "afon-core";

    private final ContainerCluster   gkeCluster;
    private       KubernetesProvider k8sProvider;
    private       HelmProvider       helmProvider;

    GcpCloudProvider(final Construct scope, final String id, String projectID, AfonPropertyProvider properties) {
        super(scope, id);
        String clusterName = properties.getValue(AfonProperty.CLUSTER_NAME);
        String gcpRegion = properties.getValue(AfonProperty.GCP_REGION);
        String vpcName     = "gke-private-vpc-" + clusterName;
        String subnetName  = "gke-private-subnet-" + clusterName;

        // Reads a fresh access token from ~/.config/gcloud via gcloud CLI.
        // This avoids the OAuth2 refresh flow that triggers RAPT on Workspace accounts.
        new GoogleProvider(
            this, "google", GoogleProviderConfig
            .builder()
            .project(projectID)
            .region(gcpRegion).zone("europe-west2-a")
            .accessToken(printGcloudAccessToken())
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
            .ipCidrRange("10.10.0.0/24")
            .region(gcpRegion)
            .network(vpc.getName())
            // Enable IP Aliasing/VPC-native networking
            .privateIpGoogleAccess(true)
            .secondaryIpRange(List.of(
                ComputeSubnetworkSecondaryIpRange
                    .builder()
                    .rangeName("pods")
                    .ipCidrRange("10.20.0.0/16")
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
        new ComputeRouterNat(
            this, "nat", ComputeRouterNatConfig
            .builder()
            .name("gke-nat")
            .router(router.getName())
            .region(gcpRegion)
            .natIpAllocateOption("AUTO_ONLY")
            .sourceSubnetworkIpRangesToNat("ALL_SUBNETWORKS_ALL_IP_RANGES")
            .build());

        String gcloudCommand = format(
            "gcloud container clusters get-credentials %s --region %s --project %s",
            clusterName, gcpRegion, PROJECT_ID);
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

    @Override
    public String getToken() {
        throw new UnsupportedOperationException("GCP uses gke-gcloud-auth-plugin; call configureK8s()");
    }

    /**
     * Uses gke-gcloud-auth-plugin instead of a static token so that credentials
     * are fetched on demand during apply without going through the OAuth2 refresh
     * flow (which triggers RAPT on Google Workspace accounts).
     */
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

    @Override
    public void configureLoadBalancerDestroy(List<Service> loadBalancers) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    /**
     * Reads a short-lived access token from {@code ~/.config/gcloud} by invoking
     * {@code gcloud auth print-access-token}.  This avoids the OAuth2 refresh flow
     * that triggers RAPT on Google Workspace accounts.
     */
    private static String printGcloudAccessToken() {
        try {
            Process p = new ProcessBuilder("gcloud", "auth", "print-access-token")
                .redirectErrorStream(true)
                .start();
            String token = new String(p.getInputStream().readAllBytes()).trim();
            int    rc    = p.waitFor();
            if (rc != 0 || token.isEmpty()) {
                throw new RuntimeException("gcloud auth print-access-token failed (exit " + rc + "): " + token);
            }
            return token;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to read gcloud access token from ~/.config/gcloud", e);
        }
    }

    // -------------------------------------------------------------------------
    // Ingress controller + TLS (mirroring AzureCloudProvider)
    // -------------------------------------------------------------------------

    synchronized HelmProvider configureHelm() {
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

}
