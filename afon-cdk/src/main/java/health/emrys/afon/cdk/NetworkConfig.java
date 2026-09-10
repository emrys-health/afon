package health.emrys.afon.cdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.kubernetes.network_policy.*;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.cdk.CdkUtils.*;
import static health.emrys.afon.cdk.JupyterHubConfig.USER_POD_LABELS;
import static health.emrys.afon.cdk.SparkWorkloads.*;
import static health.emrys.afon.config.AfonProperty.ALLOWED_DOMAINS_CSV;
import static java.lang.String.format;

/**
 * Cloud independent config for the K8s network.
 */
public class NetworkConfig {

    private static final String DOMAIN_POLICY_NAME = "allow-only-whitelisted-domains";

    /**
     * The ports the whitelisted domains may be reached on.
     */
    private static final List<String> ALLOWED_PORTS = List.of("80", "443");

    /**
     * Zones the cluster's own resolvers answer, and which a lookup therefore cannot escape
     * through. CoreDNS is authoritative for {@code cluster.local} and returns NXDOMAIN itself
     * rather than forwarding, and {@code compute.internal} goes to the VPC resolver. Neither
     * reaches a nameserver an attacker could operate, so a query smuggling data in its labels has
     * nowhere to arrive - which is why these can be wildcarded freely while external names cannot.
     */
    private static final List<String> INTERNAL_DNS_ZONES = List.of("cluster.local", "compute.internal");

    /**
     * Labels a search-domain suffix adds beyond the zone itself. The longest suffix in play is
     * {@code default.svc.cluster.local}, two labels more than {@code cluster.local}.
     */
    private static final int SEARCH_PATH_LABELS = 2;

    /**
     * The pods backing {@code emrys-master-svc}, expressed exactly as the Service selects them
     * (see {@code AwsSparkStack.sparkExternalService}). {@link SparkWorkloads#MASTER} names the
     * StatefulSet the Spark operator creates for the {@code SparkCluster}; the {@code k8s:} prefix
     * is Cilium's source qualifier for a Kubernetes label.
     */
    private static final Map<String, String> SPARK_MASTER_LABELS = Map.of(
        "k8s:io.kubernetes.pod.namespace", "default",
        "k8s:spark-role", "master");

    /**
     * One entry in {@link #allowedDomains}. Cilium offers two matchers and they are not the
     * same thing, so which one applies is stated per domain rather than guessed at from the
     * presence of a {@code *}:
     * <UL>
     * <LI>{@link #exactly} emits {@code matchName} - the name and nothing else.</LI>
     * <LI>{@link #matching} emits {@code matchPattern}, where {@code *} stands for a
     * single label, so {@code *.example.com} covers {@code a.example.com} but not
     * {@code a.b.example.com}.</LI>
     * </UL>
     */
    private record AllowedDomain(String matcher, String domain) {

        static AllowedDomain create(String domain) {
            if (domain.contains("*")) {
                return matching(domain);
            }
            return exactly(domain);
        }

        static AllowedDomain exactly(String domain) {
            return new AllowedDomain("matchName", domain);
        }

        static AllowedDomain matching(String pattern) {
            return new AllowedDomain("matchPattern", pattern);
        }

        Map<String, String> asFqdnSelector() {
            return Map.of(matcher, domain);
        }

        /**
         * How many dot-separated labels the name has, {@code a.b.com} being three.
         */
        int labelCount() {
            return domain.split("\\.").length;
        }
    }

    private final TerraformStack      tfStack;
    private final List<AllowedDomain> allowedDomains;

    public static void main(String[] args) {

        List<AllowedDomain> allowedDomains = getAllowedDomainsFrom(new FilePropertyProvider());

        List<AllowedDomain> whitelist = new ArrayList<>();
        whitelist.addAll(allowedDomains);
        whitelist.add(AllowedDomain.create(MASTER + "-svc"));

        Map<String, Object> manifest = sparkManifest(DRIVER_LABELS,
                                                     "spark-driver",
                                                     whitelist);

//        Map<String, Object> manifest = connectionManifest(
//            USER_POD_LABELS,
//            allowedDomains);
        System.out.println(format("echo '%s' | kubectl apply -f -", toJson(manifest)));
    }

    private static @NonNull List<AllowedDomain> getAllowedDomainsFrom(AfonPropertyProvider properties) {
        final List<AllowedDomain> allowedDomains;
        String                    csv = ALLOWED_DOMAINS_CSV.getValueFrom(properties.getProperties());
        allowedDomains = Arrays.stream(csv.split(",")).map(x -> AllowedDomain.create(x)).toList();
        return allowedDomains;
    }

    public NetworkConfig(TerraformStack tfStack, AfonPropertyProvider properties) {
        this.tfStack   = tfStack;
        allowedDomains = getAllowedDomainsFrom(properties);
    }

    /**
     * @param dependsOn what must exist before the policies are applied. The Cilium policy below is
     *                  applied with a local {@code kubectl}, which references nothing Terraform
     *                  knows about and so would otherwise be scheduled anywhere in the graph -
     *                  including before the cluster exists. See {@link #lockdownJupyterPods}.
     */
    public void networkPolicies(String sparkConnectorName,
                                List<ITerraformDependable> dependsOn
    ) {
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
        List<NetworkPolicySpecEgress> egress = List.of(
//            createWebEgressPolicy(),
            createDnsEgressPolicy(),
            NetworkPolicySpecEgress.builder()
                                   .to(allowedNamespacePeers)
                                   .build(),
            createSparkConnectorEgressPolicy(sparkConnectorName));
        networkPolicyFor(USER_POD_LABELS, egress);
        lockdownJupyterPods(USER_POD_LABELS, allowedDomains, dependsOn);

        lockdownSpark(dependsOn, MASTER_LABELS, "spark-master", List.of());
        lockdownSpark(dependsOn, DRIVER_LABELS, "spark-driver", List.of(AllowedDomain.create(MASTER + "-svc")));
    }

    private void lockdownSpark(List<ITerraformDependable> dependsOn,
                               Map<String, String> labels,
                               String pod,
                               List<AllowedDomain> additional
    ) {
        List<AllowedDomain> whitelist = new ArrayList();
        whitelist.addAll(additional);
        whitelist.addAll(allowedDomains);
        Map<String, Object> manifest = sparkManifest(labels, pod, whitelist);
        createWhitelistFor(dependsOn, manifest, pod + "-policy");
    }

    private static @NonNull Map<String, Object> sparkManifest(Map<String, String> labels,
                                                              String pod,
                                                              List<AllowedDomain> whitelist
    ) {
        List<Map<String, Object>> matchNames = new ArrayList<>();

        Map<String, Object> sparkEndpoints = Map.of(
            "toEndpoints", List.of(Map.of("matchLabels", MASTER_LABELS))
        );

        matchNames.addAll(
            whitelist.stream().map(x -> Map.of(x.matcher, (Object)x.domain)).toList());
        Map<String, Object> fqdns = Map.of(
            "toFQDNs", matchNames
            );

        Map<String, Object> manifest = createManifestFor(labels, pod, whitelist, List.of(sparkEndpoints, fqdns));
        return manifest;
    }

    private static @NonNull Map<String, Object> createManifestFor(Map<String, String> labels,
                                                                  String pod,
                                                                  List<AllowedDomain> whitelist,
                                                                  List<Map<String, Object>> extraEgress
    ) {
        Map<String, Object>       dnsEgress    = dnsConfigFor(whitelist);
        List<Map<String, Object>> egressConfig = new ArrayList<>();
        egressConfig.add(dnsEgress);
        egressConfig.addAll(extraEgress);

        Map<String, Object> manifest = manifestUsing(
            labels,
            pod + "-manifest",
            egressConfig);
        return manifest;
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
    private void lockdownJupyterPods(Map<String, String> labels,
                                     List<AllowedDomain> allowedDomains,
                                     List<ITerraformDependable> dependsOn
    ) {
        if (allowedDomains.isEmpty()) {
            return;
        }
        Map<String, Object> manifest = connectionManifest(labels, allowedDomains);
        createWhitelistFor(dependsOn, manifest, "jupyterNetworkPolicy");
    }

    private void createWhitelistFor(List<ITerraformDependable> dependsOn,
                                    Map<String, Object> manifest,
                                    String whiteListName
    ) {
        String manifestJson = toJson(manifest);
        List<LocalExecProvisioner> provisioners = new ArrayList<>(
            uponCreateRun("echo '" + manifestJson + "' | kubectl apply -f -"));
        provisioners.addAll(uponDestroyRun(
            "kubectl delete ciliumnetworkpolicy " + DOMAIN_POLICY_NAME + " -n default"));
        Resource.Builder.create(tfStack, whiteListName)
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

    private static @NonNull Map<String, Object> connectionManifest(Map<String, String> labels,
                                                                   List<AllowedDomain> allowedDomains
    ) {
        Map<String, Object> dnsVisibility = dnsConfigFor(allowedDomains);
        Map<String, Object> domainEgress = Map.of(
            "toFQDNs", allowedDomains.stream().map(AllowedDomain::asFqdnSelector).toList(),
            "toPorts", List.of(Map.of(
                "ports", ALLOWED_PORTS.stream()
                                      .map(port -> Map.of("port", port, "protocol", "TCP"))
                                      .toList()))
        );
        // Egress to emrys-master-svc, the Spark master (spark://emrys-master-svc:7077).
        // Named rather than listed among the whitelisted domains because a cluster-local name is
        // not something toFQDNs can express: Cilium's DNS proxy turns an answer into a CIDR
        // identity, whereas a pod inside the cluster carries a label-derived one, so the FQDN rule
        // would never match it. Traffic to a ClusterIP is DNAT'd to a backing pod before egress
        // policy is evaluated in any case, so selecting those pods on the same label the Service
        // selects on is the rule that actually applies. No toPorts, matching the domain rule
        // above: every port the master listens on (7077, 6066, 8080) is reachable.
        Map<String, Object> sparkMasterEgress = Map.of(
            "toEndpoints", List.of(Map.of("matchLabels", SPARK_MASTER_LABELS)));
        List<Map<String, Object>> allowedEgress = List.of(dnsVisibility, domainEgress, sparkMasterEgress);
        return manifestUsing(labels, DOMAIN_POLICY_NAME, allowedEgress);
    }

    private static @NonNull Map<String, Object> manifestUsing(Map<String, String> labels,
                                                              String domainPolicyName,
                                                              List<Map<String, Object>> allowedEgress
    ) {
        return Map.of(
            "apiVersion", "cilium.io/v2",
            "kind", "CiliumNetworkPolicy",
            "metadata", Map.of(
                "name", domainPolicyName,
                "namespace", "default"),
            "spec", Map.of(
                "endpointSelector", Map.of("matchLabels", labels),
                "egress", allowedEgress));
    }

    private static @NonNull Map<String, Object> dnsConfigFor(List<AllowedDomain> allowedDomains) {
        Map<String, Object> dnsVisibility = Map.of(
            "toEndpoints", List.of(Map.of(
                "matchLabels", Map.of(
                    "k8s:io.kubernetes.pod.namespace", "kube-system",
                    "k8s:k8s-app", "kube-dns"))),
            "toPorts", List.of(Map.of(
                "ports", List.of(Map.of("port", "53", "protocol", "ANY")),
                "rules", Map.of("dns", dnsQueryRules(allowedDomains)))));
        return dnsVisibility;
    }

    /**
     * The names the DNS proxy will answer. Anything else is refused, which closes DNS itself as an
     * exfiltration channel: with the previous {@code matchPattern: "*"} every name resolved, so a
     * pod could tunnel data out in the labels of a query to a nameserver it controlled, no matter
     * how tight {@code toFQDNs} was. {@code toFQDNs} governs which addresses may be *connected to*
     * and says nothing about which names may be *looked up*.
     * <p>
     * Two groups have to be here or the pods lose DNS.
     * <p>
     * First, the whitelisted domains, reusing the very selectors {@link #connectionManifest} feeds
     * to {@code toFQDNs}: Cilium only learns an address for a name whose lookup passed the proxy,
     * so a name allowed to connect but not to resolve would never work.
     * <p>
     * Second, the search-path expansions. A pod's resolver has {@code ndots:5}, so a name with
     * fewer than five dots is tried against each search domain before being tried as written -
     * {@code storage.googleapis.com} is first queried as
     * {@code storage.googleapis.com.default.svc.cluster.local}, and so on down the list. Those
     * misses have to reach CoreDNS to be answered NXDOMAIN. Refusing them instead would leave the
     * resolver to interpret a REFUSED rcode mid-search, which glibc mostly walks past but musl
     * treats far less forgivingly - a failure mode that would show up as intermittent,
     * base-image-dependent DNS breakage rather than anything obviously caused by this policy.
     * <p>
     * Hence the ladder. Cilium's {@code *} is not a glob over the whole name: {@code
     * escapeRegexpCharacters} rewrites it to {@code [-a-zA-Z0-9_]*}, which excludes the dot, so one
     * {@code *} spans exactly one label and {@code *.cluster.local} does not match
     * {@code emrys-master-svc.default.svc.cluster.local}. Covering every depth therefore means
     * emitting a pattern per depth, up to the longest whitelisted name plus
     * {@link #SEARCH_PATH_LABELS}.
     */
    private static List<Map<String, String>> dnsQueryRules(List<AllowedDomain> allowedDomains) {
        List<Map<String, String>> rules = new ArrayList<>(
            allowedDomains.stream().map(AllowedDomain::asFqdnSelector).toList());
        int deepest = allowedDomains.stream().mapToInt(AllowedDomain::labelCount).max().orElse(1)
                      + SEARCH_PATH_LABELS;
        for (String zone : INTERNAL_DNS_ZONES) {
            StringBuilder wildcards = new StringBuilder("*");
            for (int depth = 0; depth < deepest; depth++) {
                rules.add(Map.of("matchPattern", wildcards + "." + zone));
                wildcards.append(".*");
            }
        }
        return List.copyOf(rules);
    }

    private static @NonNull String toJson(Map<String, Object> manifest) {
        try {
            return new ObjectMapper().writeValueAsString(manifest);
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
    private static @NonNull NetworkPolicySpecEgress createSparkConnectorEgressPolicy(
        String sparkConnectorName
    ) {
        List<NetworkPolicySpecEgressTo> sparkConnectorPods = specEgressFor(
            egressPodSelectorFor(addMaps(
                DRIVER_LABELS, Map.of(
                    "spark.operator/spark-app-name", sparkConnectorName))));
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
