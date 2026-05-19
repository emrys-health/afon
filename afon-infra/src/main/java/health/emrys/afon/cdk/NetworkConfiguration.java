package health.emrys.afon.cdk;

import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.kubernetes.network_policy.*;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;

/**
 * Cloud independent config for the K8s network.
 */
public class NetworkConfiguration {

    private final TerraformStack tfStack;

    public NetworkConfiguration(TerraformStack tfStack) {
        this.tfStack = tfStack;
    }

    void networkPolicies(Map<String, String> labels, String sparkConnectorName) {
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
