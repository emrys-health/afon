package health.emrys.afon.cdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.kubernetes.network_policy.*;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import health.emrys.afon.config.AfonPropertyProvider;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.cdk.CdkUtils.uponCreateRun;
import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.config.AfonProperty.ALLOWED_DOMAINS_CSV;
import static health.emrys.afon.config.AfonProperty.AUDIT_KAFKA_BOOTSTRAP;

/**
 * Cloud independent config for the K8s network.
 */
public class NetworkConfig {

    private static final String DOMAIN_POLICY_NAME = "allow-only-whitelisted-domains";

    /** The ports the whitelisted domains may be reached on. */
    private static final List<String> WEB_PORTS = List.of("80", "443");

    /**
     * One entry in {@link #allowedDomains}. Cilium offers two matchers and they are not the
     * same thing, so which one applies is stated per domain rather than guessed at from the
     * presence of a {@code *}:
     * <UL>
     *     <LI>{@link #exactly} emits {@code matchName} - the name and nothing else.</LI>
     *     <LI>{@link #matching} emits {@code matchPattern}, where {@code *} stands for a
     *     single label, so {@code *.example.com} covers {@code a.example.com} but not
     *     {@code a.b.example.com}.</LI>
     * </UL>
     */
    private record AllowedDomain(String matcher, String domain) {

        static AllowedDomain exactly(String domain) {
            return new AllowedDomain("matchName", domain);
        }

        static AllowedDomain matching(String pattern) {
            return new AllowedDomain("matchPattern", pattern);
        }

        Map<String, String> asFqdnSelector() {
            return Map.of(matcher, domain);
        }
    }

    private final TerraformStack      tfStack;
    private final List<AllowedDomain> allowedDomains;

    private final AfonPropertyProvider properties;

    public NetworkConfig(TerraformStack tfStack, AfonPropertyProvider properties) {
        this.tfStack = tfStack;
        this.properties = properties;
        String csv = ALLOWED_DOMAINS_CSV.getValueFrom(properties.getProperties());
        allowedDomains = Arrays.stream(csv.split(",")).map(x -> AllowedDomain.exactly(x)).toList();
    }

    /**
     * @param dependsOn what must exist before the policies are applied. The Cilium policy below is
     *                  applied with a local {@code kubectl}, which references nothing Terraform
     *                  knows about and so would otherwise be scheduled anywhere in the graph -
     *                  including before the cluster exists. See {@link #whitelistDomains}.
     */
    public void networkPolicies(Map<String, String> labels,
                                String sparkConnectorName,
                                List<ITerraformDependable> dependsOn) {
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
        List<NetworkPolicySpecEgress> egress = new ArrayList<>(List.of(
//            createWebEgressPolicy(),
            createDnsEgressPolicy(),
            NetworkPolicySpecEgress.builder()
                                   .to(allowedNamespacePeers)
                                   .build(),
            createSparkConnectorEgressPolicy(sparkConnectorName)));
        // HDRS (Patch 17): the audit perimeter sidecar runs INSIDE the notebook pod
        // (Patches 14-16), so it shares this policy. Without a rule for it the sensor
        // cannot reach Kafka or the schema registry and audit events are dropped with
        // no error anywhere - the failure mode a health-data platform can least afford.
        // Gated on the same config as the sidecar itself: no AUDIT_KAFKA_BOOTSTRAP, no
        // sidecar, no rule.
        createAuditEgressPolicy().ifPresent(egress::add);
        networkPolicyFor(labels, egress);
        whitelistDomains(labels, allowedDomains, dependsOn);
    }

    /**
     * Whitelists egress to a set of domain names.
     * <p>
     * A Kubernetes {@link NetworkPolicy} only understands CIDRs, so a name whose address is
     * decided by a CDN and changes underneath us cannot be expressed there - hence the
     * hard-coded IP block in {@link #createWebEgressPolicy()}. Cilium (our CNI) does
     * understand names: its DNS proxy watches the pod's own DNS answers and programs the
     * addresses it sees into the policy at runtime, which is what {@code toFQDNs} below
     * relies on. That proxy is gated cluster-wide by the {@code enable-fqdn-policy}
     * cilium-config key, set when the Cilium chart is installed.
     * <p>
     * The first egress rule is not optional: Cilium can only learn an address for a name if
     * the lookup itself passes through its DNS proxy, which happens only when a rule asks
     * for L7 DNS visibility.
     * <p>
     * A CiliumNetworkPolicy is additive to the policy {@link #networkPolicyFor} creates -
     * Cilium ORs every rule selecting an endpoint - so this widens the allowlist rather than
     * replacing it.
     * <p>
     * It is applied with kubectl rather than a {@code kubernetes_manifest} resource because
     * that resource contacts the API server during plan, before the cilium.io CRDs are
     * registered, and fails with "Failed to construct REST client".
     * <p>
     * That kubectl reads the ambient {@code ~/.kube/config} and interpolates nothing from the
     * stack, so Terraform sees no dependency and is free to run it first - against whichever
     * cluster the kubeconfig still names. On a rebuild that is the *previous*, destroyed cluster,
     * whose DNS record is gone, and the apply dies with "dial tcp: lookup
     * &lt;id&gt;.gr7.&lt;region&gt;.eks.amazonaws.com: no such host". Hence {@code dependsOn}: it must
     * carry whatever refreshes the kubeconfig (the {@code kubeconfigUpdater} resource on AWS) and
     * the Cilium release that registers the {@code cilium.io/v2} CRD this manifest needs.
     */
    private void whitelistDomains(Map<String, String> labels,
                                  List<AllowedDomain> allowedDomains,
                                  List<ITerraformDependable> dependsOn) {
        if (allowedDomains.isEmpty()) {
            return;
        }
        Map<String, Object> dnsVisibility = Map.of(
            "toEndpoints", List.of(Map.of("matchLabels", Map.of(
                "k8s:io.kubernetes.pod.namespace", "kube-system",
                "k8s:k8s-app", "kube-dns"))),
            "toPorts", List.of(Map.of(
                "ports", List.of(Map.of("port", "53", "protocol", "ANY")),
                "rules", Map.of("dns", List.of(Map.of("matchPattern", "*"))))));
        Map<String, Object> domainEgress = Map.of(
            "toFQDNs", allowedDomains.stream().map(AllowedDomain::asFqdnSelector).toList(),
            "toPorts", List.of(Map.of(
                "ports", WEB_PORTS.stream()
                                  .map(port -> Map.of("port", port, "protocol", "TCP"))
                                  .toList())));
        Map<String, Object> manifest = Map.of(
            "apiVersion", "cilium.io/v2",
            "kind", "CiliumNetworkPolicy",
            "metadata", Map.of(
                "name", DOMAIN_POLICY_NAME,
                "namespace", "default"),
            "spec", Map.of(
                "endpointSelector", Map.of("matchLabels", labels),
                "egress", List.of(dnsVisibility, domainEgress)));

        String manifestJson = toJson(manifest);
        List<LocalExecProvisioner> provisioners = new ArrayList<>(
            uponCreateRun("echo '" + manifestJson + "' | kubectl apply -f -"));
        provisioners.addAll(uponDestroyRun(
            "kubectl delete ciliumnetworkpolicy " + DOMAIN_POLICY_NAME + " -n default"));
        Resource.Builder.create(tfStack, "domainWhitelistPolicy")
                        .dependsOn(dependsOn)
                        // A provisioner runs on create only, so without a trigger an edit to
                        // ALLOWED_DOMAINS is a no-op against a cluster that already has the policy:
                        // the resource is unchanged, nothing re-runs, and the cluster silently keeps
                        // yesterday's allowlist while the source says otherwise. Keying on the
                        // manifest makes any edit to the list replace the resource, which deletes
                        // and re-applies the policy.
                        .triggers(Map.of("manifest", manifestJson))
                        .provisioners(provisioners)
                        .build();
    }

    /**
     * HDRS (Patch 19): serialise with map keys SORTED.
     *
     * <p>The manifest is built from {@code Map.of(...)}, whose iteration order is
     * deliberately randomised per JVM run, so the default serialisation produces a
     * different string on every synth even when nothing has changed. That string is
     * the {@code triggers} value on {@code domainWhitelistPolicy}, so the resource
     * was replaced on EVERY apply - deleting and re-applying the CiliumNetworkPolicy
     * each time, which leaves a window with no FQDN egress allowlist on a cluster
     * whose whole point is restricting egress. Confirmed by hashing two consecutive
     * synths of identical input: same length, different digest.
     *
     * <p>Sorting makes the output a pure function of the content, so the trigger is
     * stable and the policy is only re-applied when the allowlist actually changes -
     * which is what the trigger was there to do.
     */
    private static @NonNull String toJson(Map<String, Object> manifest) {
        try {
            return new ObjectMapper()
                .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .writeValueAsString(manifest);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize " + DOMAIN_POLICY_NAME, e);
        }
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
    /**
     * HDRS (Patch 17): egress to the audit platform's Kafka and schema registry, for the
     * perimeter sidecar that Patches 14-16 put in the notebook pod. Upstream's policy allows
     * the notebook DNS, the hub and Spark Connect and drops everything else, which includes
     * the sensor's own path off the pod.
     *
     * <p>Selected by namespace label rather than name so it keeps working if the audit
     * platform is ever deployed to a differently-named namespace, and narrowed to the two
     * ports that carry the audit protocol - 9092 for Kafka, 8081 for the schema registry.
     */
    private java.util.Optional<NetworkPolicySpecEgress> createAuditEgressPolicy() {
        String kafka = AUDIT_KAFKA_BOOTSTRAP.getValueFrom(properties.getProperties(), "");
        if (kafka.isEmpty()) {
            return java.util.Optional.empty();
        }
        List<NetworkPolicySpecEgressTo> auditPods = List.of(
            NetworkPolicySpecEgressTo
                .builder()
                .namespaceSelector(NetworkPolicySpecEgressToNamespaceSelector
                                       .builder()
                                       .matchLabels(Map.of("app.kubernetes.io/part-of", "audit-platform"))
                                       .build())
                .build());
        List<NetworkPolicySpecEgressPorts> auditPorts = List.of(
            NetworkPolicySpecEgressPorts.builder().protocol("TCP").port("9092").build(),
            NetworkPolicySpecEgressPorts.builder().protocol("TCP").port("8081").build());
        return java.util.Optional.of(NetworkPolicySpecEgress
                                         .builder()
                                         .to(auditPods)
                                         .ports(auditPorts)
                                         .build());
    }

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
        String                          hdrsWebsite = "185.230.220.0/8";
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
