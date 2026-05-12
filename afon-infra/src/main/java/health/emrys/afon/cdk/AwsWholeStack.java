package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.Fn;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.TerraformResourceLifecycle;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.provider.HelmProviderKubernetes;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.kubernetes.network_policy.*;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccount;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccountMetadata;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.config.AfonPropertiesLoader;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.infra.Defaults.SPARK_CONNECTOR_NAME;
import static health.emrys.afon.infra.Defaults.SPARK_SERVICE_ACCOUNT_NAME;

/***
 * Creates the markup to create the full stack in AWS.
 * Note: it does not create anything other than JSON files!
 * You need to run tofu on those files for the infra to be provisioned.
 * Don't forget to
 * <UL>
 *     <LI>
 *          set environment variables, something like <pre>KUBE_CONFIG_PATH=/home/henryp/.kube/config</pre>
 *     </LI>
 *      <LI>
 *          <pre>unalias aws</pre> if you're using the Dockerized version and put the actual binary in your
 *          <pre>PATH</pre>
 *      </LI>
 * </UL>
 * when you run the output
 */
public class AwsWholeStack extends AwsKubernetesPolaris {

    static final Logger logger                     = org.slf4j.LoggerFactory.getLogger(AwsWholeStack.class);
    static final String NO_INSTALLATION_FINALIZERS = "kubectl patch installation default -p " +
                                                     "'{\"metadata\":{\"finalizers\":[]}}' --type=merge";

    /**
     * Password the Jupyter VNC server is configured with. Kept in-cluster only — the
     * Guacamole web UI itself is intended to live behind oauth2-proxy.
     */
    protected final List<Service> loadBalancers;
    protected final SparkConfig   sparkOperatorConfig;
    private         String        DELETE_CRDS;

    public AwsWholeStack(final Construct scope,
                         final SupportedCloudsEnum id,
                         final Properties properties) {
        super(scope, id, properties);
        List<ITerraformDependable> dependables = cloudProvider.getDependables();
        JupyterConfig jupyter = new JupyterAwsConfig(
            this,
            cloudProvider,
            properties);
        networkPolicies(jupyter.deployJupyter(dependables), SPARK_CONNECTOR_NAME);
        loadBalancers = List.of(); // Jupyter is now exposed via ALB ingress, not a raw LoadBalancer Service

        HelmProviderKubernetes kubernetes = HelmProviderKubernetes
            .builder()
            .host(cloudProvider.getClusterEndpoint())
            .clusterCaCertificate(Fn.base64decode(cloudProvider.getBase64CertData()))
            .token(cloudProvider.getToken())
            .build();
        HelmProvider helm = HelmProvider.Builder
            .create(this, "helm")
            .kubernetes(kubernetes)
            .build();
        this.addOverride(
            "terraform.required_providers.aws", Map.of(
                "source", "hashicorp/aws",
                "version", "6.27.0"   // 👈 Pin exact version
                                                      ));
        this.addOverride(
            "terraform.required_providers.helm", Map.of(
                "source", "hashicorp/helm",
                "version", "3.1.1"   // 👈 Pin exact version
                                                       ));
        this.addOverride(
            "terraform.required_providers.kubernetes", Map.of(
                "source", "hashicorp/kubernetes",
                "version", "3.0.1"   // 👈 Pin exact version
                                                             ));


        csiSecretsDriver(dependables);

        ServiceAccountMetadata accountMetadata = ServiceAccountMetadata
            .builder()
            .name(SPARK_SERVICE_ACCOUNT_NAME)
            .namespace("default")
            .build();
        ServiceAccount.Builder
            .create(this, "sparkApplicationSa")
            .metadata(accountMetadata)
            .build();

        cloudProvider.configureLoadBalancerDestroy(loadBalancers);
        sparkOperatorConfig = new SparkConfig(this, "sparkop", dependables);
    }

    protected void unregisterCRDs(List<? extends ITerraformDependable> dependsOn) {
        DELETE_CRDS = """
                      for i in {1..5}; do {
                          echo iteration $i
                      
                          for CRD in $(kubectl get crds | awk '{print $1}' | grep -v spark) ; do {
                           echo $CRD
                           timeout 30s kubectl patch crd $CRD --type=json -p='[{"op": "remove", "path": "/metadata/finalizers"}]' || echo could not patch $CRD 
                           timeout 30s kubectl delete crd $CRD  --force || echo could not delete $CRD
                          } done & 
                      
                          sleep 60
                      } done
                      """;
        Resource.Builder
            .create(this, "cleanupCRDs")
            .dependsOn(dependsOn)
            .provisioners(uponDestroyRun(DELETE_CRDS))
            .build();
        Resource.Builder
            .create(this, "cleanupCRDsNoDeps")
            .provisioners(uponDestroyRun(DELETE_CRDS))
            .build();
    }

    private void networkPolicies(Map<String, String> labels, String sparkConnectorName) {
        List<NetworkPolicySpecEgressTo> clusterPodSelectors = specEgressFor(
            egressPodSelectorFor(Map.of()));
        List<NetworkPolicySpecEgressTo> clusterIps = ipBlocksFor("10.96.0.0/12");
        List<NetworkPolicySpecEgressPorts> clusterPorts = List.of(
            NetworkPolicySpecEgressPorts.builder().protocol("TCP").port("443").build()
                                                                 );
        List<NetworkPolicySpecEgressTo> sparkOperatorPods = List.of(
            egressPolicyFor(
                egressToNamespaceFor(Map.of(
                    "kubernetes.io/metadata.name", "spark-operator")),
                egressPodSelectorFor(Map.of(
                    "app.kubernetes.io/name", "spark-operator"))));

        List<NetworkPolicySpecEgress> egress = List.of(
            createWebEgressPolicy(),
            createDnsEgressPolicy(),
            NetworkPolicySpecEgress.builder()
                                   .to(clusterPodSelectors)
                                   .build(),
            NetworkPolicySpecEgress.builder()
                                   .to(clusterIps)
                                   .ports(clusterPorts)
                                   .build(),
            createSparkConnectorEgressPolicy(sparkConnectorName),
            NetworkPolicySpecEgress.builder()
                                   .to(sparkOperatorPods)
                                   .build());
        networkPolicyFor(labels, egress);
    }

    private static @NonNull NetworkPolicySpecEgress createSparkConnectorEgressPolicy(
        String sparkConnectorName) {
        List<NetworkPolicySpecEgressTo> sparkConnectorPods = specEgressFor(
            egressPodSelectorFor(
                Map.of("app.kubernetes.io/name", sparkConnectorName)));
        List<NetworkPolicySpecEgressPorts> sparkConnectorPorts = List.of(
            NetworkPolicySpecEgressPorts.builder().protocol("TCP").port("15002").build());
        return NetworkPolicySpecEgress
            .builder()
            .to(sparkConnectorPods)
            .ports(sparkConnectorPorts)
            .build();
    }

    private static @NonNull NetworkPolicySpecEgress createWebEgressPolicy() {
        String                          bbcNews     = "151.101.0.0/16";
        List<NetworkPolicySpecEgressTo> externalIps = ipBlocksFor(bbcNews);
        List<NetworkPolicySpecEgressPorts> webPorts = List.of(
            NetworkPolicySpecEgressPorts.builder().protocol("TCP").port("80").build(),
            NetworkPolicySpecEgressPorts.builder().protocol("TCP").port("443").build());
        NetworkPolicySpecEgress webEgress = NetworkPolicySpecEgress
            .builder()
            .to(externalIps)
            .ports(webPorts)
            .build();
        return webEgress;
    }

    private static @NonNull NetworkPolicySpecEgress createDnsEgressPolicy() {
        List<NetworkPolicySpecEgressPorts> dnsPorts = List.of(
            NetworkPolicySpecEgressPorts.builder().protocol("UDP").port("53").build(),
            NetworkPolicySpecEgressPorts.builder().protocol("TCP").port("53").build());
        NetworkPolicySpecEgressTo dnsNetworkPolicy
            = egressPolicyFor(
            egressToNamespaceFor(Map.of(
                "kubernetes.io/metadata.name", "kube-system")),
            egressPodSelectorFor(Map.of("k8s-app", "kube-dns")));
        NetworkPolicySpecEgress dnsEgress = NetworkPolicySpecEgress
            .builder()
            .to(List.of(dnsNetworkPolicy))
            .ports(dnsPorts)
            .build();
        return dnsEgress;
    }

    private static @NonNull NetworkPolicySpecEgressTo egressPolicyFor(
        NetworkPolicySpecEgressToNamespaceSelector sparkNamespace,
        NetworkPolicySpecEgressToPodSelector sparkPods) {
        return NetworkPolicySpecEgressTo
            .builder()
            .namespaceSelector(sparkNamespace)
            .podSelector(sparkPods)
            .build();
    }

    private void networkPolicyFor(Map<String, String> labels, List<NetworkPolicySpecEgress> egress) {
        NetworkPolicySpecPodSelector labelPods = NetworkPolicySpecPodSelector
            .builder()
            .matchLabels(labels)
            .build();
        NetworkPolicySpec spec = NetworkPolicySpec
            .builder()
            .podSelector(labelPods)
            .policyTypes(List.of("Egress"))
            .egress(egress)
            .build();
        NetworkPolicy.Builder.create(this, "secretAppEgressPolicy")
                             .metadata(getMetadata())
                             .spec(spec)
                             .build();
    }

    private static @NonNull NetworkPolicyMetadata getMetadata() {
        return NetworkPolicyMetadata
            .builder()
            .name("allow-only-specific-egress")
            .namespace("default")
            .build();
    }

    private static @NonNull List<NetworkPolicySpecEgressTo> specEgressFor(NetworkPolicySpecEgressToPodSelector sparkConnectorPodSelector) {
        return List.of(
            NetworkPolicySpecEgressTo
                .builder()
                .podSelector(sparkConnectorPodSelector)
                .build());
    }

    private static @NonNull NetworkPolicySpecEgressToPodSelector egressPodSelectorFor(Map<String, String> kubeDnsLabels) {
        return NetworkPolicySpecEgressToPodSelector.builder()
                                                   .matchLabels(kubeDnsLabels)
                                                   .build();
    }

    private static @NonNull NetworkPolicySpecEgressToNamespaceSelector egressToNamespaceFor(Map<String, String> sparkOperatorLables) {
        return NetworkPolicySpecEgressToNamespaceSelector
            .builder()
            .matchLabels(sparkOperatorLables)
            .build();
    }

    private static @NonNull List<NetworkPolicySpecEgressTo> ipBlocksFor(String clusterCIDR) {
        return List.of(
            NetworkPolicySpecEgressTo
                .builder()
                .ipBlock(NetworkPolicySpecEgressToIpBlock
                             .builder()
                             .cidr(clusterCIDR)
                             .build())
                .build());
    }

    private void csiSecretsDriver(List<@NotNull ITerraformDependable> eksDependOn) {
        Release.Builder.create(this, "secrets-store-csi-driver")
                       .dependsOn(eksDependOn)
                       .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                       .name("csi-secrets-store") // The release name
                       .chart("secrets-store-csi-driver") // The chart name within the repository
                       .version("1.5.4")
                       .repository("https://kubernetes-sigs.github.io/secrets-store-csi-driver/charts")
                       .namespace("kube-system") // Target Kubernetes namespace
                       .timeout(300) // Wait up to 300 seconds for the deployment to stabilize
                       .atomic(true) // If installation fails, completely uninstall the release
                       .build();
    }

    public static void main(String[] args) {
        final App app = new App();
        new AwsWholeStack(app, ID, new AfonPropertiesLoader().getProperties());
        app.synth();
        logger.warn("Don't forget to export KUBE_CONFIG_PATH=$HOME/.kube/config and unalias aws before running tofu " +
                    "-apply");
    }
}