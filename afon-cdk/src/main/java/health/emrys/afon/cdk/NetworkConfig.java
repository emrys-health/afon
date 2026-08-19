package health.emrys.afon.cdk;

import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.kubernetes.network_policy.*;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;

/**
 * Cloud independent config for the K8s network.
 */
public class NetworkConfig {

    private final TerraformStack tfStack;

    public NetworkConfig(TerraformStack tfStack) {
        this.tfStack = tfStack;
    }

    public void networkPolicies(Map<String, String> labels, String sparkConnectorName) {
        // The only default-namespace peers a notebook pod may reach. An egress `to` entry carrying
        // just a podSelector is scoped to the policy's own namespace, so this is exactly "these two
        // pods in default, nothing else": Guacamole (streams the pod's VNC desktop) and the
        // JupyterHub hub (the single-user server must register with it or the spawn times out).
        // Everything else in the namespace -- Polaris, Keycloak, Trino, the monitoring stack, other
        // users' notebooks -- is dropped.
        // Guacamole is deliberately absent. guacd dials the notebook's VNC port, so the desktop
        // pixels travel back on a connection Guacamole itself opened; conntrack permits those
        // replies and the notebook never initiates to Guacamole. Listing it would only have granted
        // the notebook the ability to dial Guacamole, which nothing requires.
        List<NetworkPolicySpecEgressTo> allowedNamespacePeers = List.of(
            podEgressTo(JupyterHubConfig.HUB_LABELS));
        // Two rules were deleted here after validating every selector in this file against a live
        // cluster. Both matched zero pods, so removing them is a no-op at runtime, not a loosening:
        //
        //   * egress to a "spark-operator" namespace. SparkConfig deploys the operator into default
        //     and nothing in the stack ever creates a namespace of that name, so the rule was inert.
        //     It is unnecessary regardless: under Spark Connect the notebook is a thin client that
        //     speaks to the driver alone, never to the operator.
        //
        //   * egress to 10.96.0.0/12:443 -- the upstream-Kubernetes default service CIDR, evidently
        //     intended to permit calls to the kube API. EKS assigns this cluster 172.20.0.0/16, so
        //     it matched nothing. Correcting the CIDR would not have helped either: Cilium rewrites
        //     a ClusterIP to its backend pod address before evaluating policy, so a service-CIDR
        //     ipBlock is not the mechanism for allowing that traffic. jupyterhub-singleuser never
        //     calls the kube API, so no rule is needed at all.
        //
        // Egress is default-deny across every namespace once this policy selects a pod, so the list
        // below is the complete set of destinations a spawned notebook may reach.
        List<NetworkPolicySpecEgress> egress = List.of(
            createWebEgressPolicy(),
            createDnsEgressPolicy(),
            NetworkPolicySpecEgress.builder()
                                   .to(allowedNamespacePeers)
                                   .build(),
            createSparkConnectorEgressPolicy(sparkConnectorName));
        networkPolicyFor(labels, egress);
    }

    /**
     * Egress to the Spark Connect endpoint, restricted to port 15002.
     *
     * <p>Selected on the labels the Spark operator stamps on the pods it manages, not on
     * {@code app.kubernetes.io/name}. The operator derives that one as {@code <app>-driver} --
     * {@code spark-connector-driver} -- so the previous selector
     * {@code app.kubernetes.io/name=spark-connector} matched no pods at all and this rule was inert.
     * That went unnoticed because a blanket "all pods in the namespace" rule was permitting the
     * traffic instead; once that was narrowed, the mistake became load-bearing.
     *
     * <p>{@code spark.operator/spark-app-name} carries the SparkApplication name verbatim, so it
     * lines up with {@code SPARK_CONNECTOR_NAME} with no suffix convention to drift out of step.
     * {@code spark-role=driver} narrows it to the single pod serving Connect: executors carry the
     * same app name but do not listen on 15002.
     */
    private static @NonNull NetworkPolicySpecEgress createSparkConnectorEgressPolicy(
        String sparkConnectorName
    ) {
        List<NetworkPolicySpecEgressTo> sparkConnectorPods = specEgressFor(
            egressPodSelectorFor(Map.of(
                "spark.operator/spark-app-name", sparkConnectorName,
                "spark-role", "driver")));
        List<NetworkPolicySpecEgressPorts> sparkConnectorPorts = List.of(
            NetworkPolicySpecEgressPorts.builder().protocol("TCP").port("15002").build());
        return NetworkPolicySpecEgress
            .builder()
            .to(sparkConnectorPods)
            .ports(sparkConnectorPorts)
            .build();
    }

    private static @NonNull NetworkPolicySpecEgress createWebEgressPolicy() {
        String                          hdrsWebsite = "185.230.220/8";
        List<NetworkPolicySpecEgressTo> externalIps = ipBlocksFor(hdrsWebsite);
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
        NetworkPolicySpecEgressToPodSelector sparkPods
    ) {
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
        NetworkPolicy.Builder.create(tfStack, "secretAppEgressPolicy")
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

    /**
     * A single egress peer selected by pod labels. With no namespaceSelector alongside it, this
     * matches only pods in the NetworkPolicy's own namespace.
     */
    private static @NonNull NetworkPolicySpecEgressTo podEgressTo(Map<String, String> podLabels) {
        return NetworkPolicySpecEgressTo
            .builder()
            .podSelector(egressPodSelectorFor(podLabels))
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

}
