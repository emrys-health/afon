package health.emrys.afon.ops.kube.tools;

import health.emrys.afon.cdk.azure.AzureCloudProvider;
import health.emrys.afon.cdk.gcp.GcpCloudProvider;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonCloudProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.kube.KubeClient;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.NetworkingV1Api;
import io.kubernetes.client.openapi.models.V1Ingress;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.util.PatchUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static java.lang.String.format;

/**
 * Shows, sets or removes, live, the restriction of who can call the Polaris ingress of a cluster, as the script
 * {@code kube/polaris-whitelist.sh} does, which the next deploy sets again as the config says.
 * <ul>
 *   <li>The annotation is {@value #AWS_ANNOTATION} on AWS, and {@value #NGINX_ANNOTATION} on Azure and GCP, which only works with
 *   {@code externalTrafficPolicy: Local} on the nginx controller's service, so {@link #set} sets that too.</li>
 *   <li>What is set is the CIDRs given, else the configured {@code <CLOUD>_POLARIS_ALLOWED_CIDRS}, plus the cluster's own addresses,
 *   as the deploy adds them, so that its pods calling Polaris by its public domain name are not locked out.</li>
 *   <li>With the caller, the address of this machine is added to those, which lets it call Polaris, eg to run the Polaris steps of
 *   {@code setup}, without taking the restriction off for everybody. {@link #set} again, without the caller, takes it off.</li>
 * </ul>
 */
public class PolarisWhitelister {

    static final Logger logger = LoggerFactory.getLogger(PolarisWhitelister.class);

    public static final String AWS_ANNOTATION   = "alb.ingress.kubernetes.io/inbound-cidrs";
    public static final String NGINX_ANNOTATION = "nginx.ingress.kubernetes.io/whitelist-source-range";

    private static final String CALLER_IP_URL = "https://ifconfig.me";
    private static final Pattern CIDR = Pattern.compile("^[0-9a-fA-F:.]+/[0-9]+$");
    private static final Pattern IPV4 = Pattern.compile("^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$");

    /**
     * How the ingress is restricted.
     *
     * @param restriction   the annotation, empty if it is not set, ie not restricted
     * @param trafficPolicy the traffic policy of the ingress controller, empty on AWS, which has none
     */
    public record Status(Optional<String> restriction, Optional<String> trafficPolicy) {
        public boolean isRestricted() {
            return restriction.isPresent();
        }

        public boolean keepsCallerIps() {
            return trafficPolicy.map("Local"::equals).orElse(true);
        }
    }

    private final AfonCloud               cloud;
    private final String                  context;
    private final AfonPropertyProvider    properties;
    private final Ingress                 ingress;
    private final Supplier<Optional<String>> clusterIp;
    private final Supplier<String>        callerIp;

    /**
     * Looks the addresses up as the script does: the cluster's with the CLI of its cloud, and this machine's with
     * {@code curl -4 https://ifconfig.me}, or the service of the env var {@code CALLER_IP_URL}.
     *
     * The cluster is that of the current kubectl context of the client, whose kind must be that of the cloud, as changing the ingress
     * of another cluster is not what was asked.
     */
    public PolarisWhitelister(AfonCloud cloud, AfonPropertyProvider properties, KubeClient client) {
        this(cloud, properties, client, () -> clusterIpOf(cloud, client.getContext()), PolarisWhitelister::callerIpFromService);
    }

    /**
     * @param clusterIp the egress IP of the cluster as its cloud has it, or empty if it cannot be found
     * @param callerIp  the IPv4 address of this machine, or fails
     */
    public PolarisWhitelister(AfonCloud cloud, AfonPropertyProvider properties, KubeClient client,
                              Supplier<Optional<String>> clusterIp, Supplier<String> callerIp) {
        this.cloud      = cloud;
        this.context    = client.getContext();
        this.properties = properties;
        this.ingress    = new Ingress(client.getApiClient());
        this.clusterIp  = clusterIp;
        this.callerIp   = callerIp;
    }

    public String annotation() {
        return cloud == AfonCloud.AWS ? AWS_ANNOTATION : NGINX_ANNOTATION;
    }

    public Status show() {
        verifyCluster();
        return new Status(ingress.annotation(annotation()), controller().map(ingress::trafficPolicy));
    }

    /**
     * Restricts the ingress.
     *
     * @param withCaller whether to add the address of this machine to the CIDRs
     * @param given      the CIDRs to restrict it to, else the configured ones
     * @return the CIDRs that it is restricted to
     * @throws IllegalArgumentException if a CIDR given is not one
     * @throws IllegalStateException    if there is nothing to restrict it to, or the address of this machine cannot be learnt:
     *                                  the cluster is then left as it is
     */
    public List<String> set(boolean withCaller, Optional<List<String>> given) {
        verifyCluster();
        given.ifPresent(cidrs -> cidrs.forEach(PolarisWhitelister::requireCidr));
        // before anything is changed
        List<String> cidrs = restrictionCidrs(withCaller, given);
        logger.info("Restricting the Polaris ingress to {}", String.join(",", cidrs));
        controller().ifPresent(service -> ingress.patchService(service, "Local"));
        ingress.patchAnnotation(annotation(), String.join(",", cidrs));
        return cidrs;
    }

    /**
     * @return whether it was restricted
     */
    public boolean remove() {
        verifyCluster();
        if (ingress.annotation(annotation()).isEmpty()) {
            return false;
        }
        ingress.patchAnnotation(annotation(), null);
        return true;
    }

    // The CIDRs that the deploy would restrict it to, with the cluster's own addresses
    private List<String> restrictionCidrs(boolean withCaller, Optional<List<String>> given) {
        Set<String> cidrs = new LinkedHashSet<>(given.orElseGet(this::configuredCidrs));
        if (withCaller) {
            String callerCidr = callerIp.get() + "/32";
            logger.info(cidrs.contains(callerCidr) ? "This machine, {}, is already in the list" : "Admitting this machine, {}",
                        callerCidr);
            cidrs.add(callerCidr);
        }
        Optional<String> egressIp = egressIp();
        switch (cloud) {
            case AWS -> {
                // the cluster's egress IP last, wherever it is configured
                String egressCidr = egressIp.orElseThrow(() -> new IllegalStateException(
                    "No egress IP for the cluster: not restricting the Polaris ingress to the CIDRs, which would lock out its pods"))
                                    + "/32";
                cidrs.remove(egressCidr);
                cidrs.add(egressCidr);
            }
            case AZURE -> {
                egressIp.ifPresent(ip -> cidrs.add(ip + "/32"));
                cidrs.add(AzureCloudProvider.POD_CIDR);
            }
            case GCP -> {
                egressIp.ifPresent(ip -> cidrs.add(ip + "/32"));
                cidrs.add(GcpCloudProvider.POD_CIDR);
                cidrs.add(GcpCloudProvider.NODE_CIDR);
            }
        }
        if (cidrs.isEmpty()) {
            throw new IllegalStateException("No CIDRs to restrict the Polaris ingress to");
        }
        return List.copyOf(cidrs);
    }

    // The cluster's own address is preferred to the configured one, which may be stale, eg if the stack was deployed without it,
    // but the CLI of the cloud need not be installed or logged in
    private Optional<String> egressIp() {
        String configured = properties.getCloudValue(cloud, AfonCloudProperty.EGRESS_IP, "").trim();
        Optional<String> found = clusterIp.get();
        if (found.isEmpty()) {
            if (cloud != AfonCloud.GCP) {
                logger.warn("No egress IP found for the cluster {}: using the configured '{}'", context, configured);
            }
            return configured.isEmpty() ? Optional.empty() : Optional.of(configured);
        }
        if (!configured.isEmpty() && !configured.equals(found.get())) {
            logger.warn("The egress IP of the cluster, {}, is not the configured {}: using it. Redeploy the stack to pin it to the "
                        + "configured one", found.get(), configured);
        }
        return found;
    }

    private List<String> configuredCidrs() {
        return Arrays.stream(properties.getCloudValue(cloud, AfonCloudProperty.POLARIS_ALLOWED_CIDRS, "").split(","))
                     .map(String::trim)
                     .filter(cidr -> !cidr.isEmpty())
                     .toList();
    }

    private Optional<String> controller() {
        return cloud == AfonCloud.AWS ? Optional.empty() : ingress.controllerService();
    }

    private void verifyCluster() {
        switch (cloud) {
            case AWS -> {
                if (!context.startsWith("arn:aws:eks:")) {
                    throw new IllegalStateException(format("The kubectl context '%s' is not an EKS cluster: point kubectl at the AWS one, "
                                                           + "eg with 'afon-cli.sh aws-update-kube-config tre'", context));
                }
            }
            case GCP -> {
                if (!context.startsWith("gke_")) {
                    throw new IllegalStateException(format("The kubectl context '%s' is not a GKE cluster: point kubectl at the GCP one, "
                                                           + "eg with 'afon-cli.sh gcp-update-kube-config tre'", context));
                }
            }
            case AZURE -> {
                if (ingress.controllerService().isEmpty()) {
                    throw new IllegalStateException(format("The kubectl context '%s' has no nginx ingress controller, as the Azure "
                                                           + "stacks have: point kubectl at the AKS one, eg with 'afon-cli.sh "
                                                           + "azure-update-kube-config tre'", context));
                }
            }
        }
        if (!ingress.exists()) {
            throw new IllegalStateException(format("There is no Polaris ingress (%s in the %s namespace) in the kubectl context '%s'",
                                                   Ingress.NAME, Ingress.NAMESPACE, context));
        }
    }

    private static void requireCidr(String cidr) {
        if (!CIDR.matcher(cidr).matches()) {
            throw new IllegalArgumentException(format("'%s' is not a CIDR, eg 203.0.113.7/32", cidr));
        }
    }

    /**
     * The IPv4 address of this machine as the service sees it. Asked for IPv4 only (curl -4): the Polaris domain names only have IPv4
     * addresses, so that is what a call to Polaris comes from, whereas a dual-stack machine is otherwise told its IPv6 address.
     */
    private static String callerIpFromService() {
        String url = Optional.ofNullable(System.getenv("CALLER_IP_URL")).filter(u -> !u.isBlank()).orElse(CALLER_IP_URL);
        String address = run("curl", "-4", "-s", "--max-time", "10", url).replaceAll("\\s", "");
        if (!IPV4.matcher(address).matches()) {
            throw new IllegalStateException(format(
                "Could not learn the IPv4 address of this machine from %s (got '%s'): is it reachable over IPv4?",
                url, address.isEmpty() ? "nothing" : address));
        }
        return address;
    }

    // The egress IP of the cluster with the CLI of its cloud, as the scripts do: for AWS that of its NAT gateway, named after the
    // cluster as AwsComponentNames.getNatGatewayName has it, and for Azure the first effective outbound IP of its load balancer.
    // GCP has none to look up: it is the configured one, if any.
    private static Optional<String> clusterIpOf(AfonCloud cloud, String context) {
        return switch (cloud) {
            case AWS -> {
                // As written by 'aws eks update-kubeconfig': arn:aws:eks:<region>:<account>:cluster/<cluster>
                String[] arn = context.split(":");
                yield arn.length != 6 || !arn[5].startsWith("cluster/") ? Optional.empty() : found(run(
                    "aws", "ec2", "describe-nat-gateways", "--region", arn[3],
                    "--filter", "Name=tag:Name,Values=cdktf-eks-nat-" + arn[5].substring("cluster/".length()),
                    "Name=state,Values=available",
                    "--query", "NatGateways[0].NatGatewayAddresses[0].PublicIp", "--output", "text"));
            }
            case AZURE -> {
                String group = run("az", "aks", "list", "--query", "[?name=='" + context + "'].resourceGroup | [0]", "--output", "tsv");
                String ipId  = found(group).isEmpty() ? "" : run(
                    "az", "aks", "show", "--name", context, "--resource-group", group,
                    "--query", "networkProfile.loadBalancerProfile.effectiveOutboundIPs[0].id", "--output", "tsv");
                yield found(ipId).isEmpty() ? Optional.empty() : found(run(
                    "az", "network", "public-ip", "show", "--ids", ipId, "--query", "ipAddress", "--output", "tsv"));
            }
            case GCP -> Optional.empty();
        };
    }

    private static Optional<String> found(String output) {
        return output.isEmpty() || output.equals("None") ? Optional.empty() : Optional.of(output);
    }

    // What the command prints, trimmed, or nothing if it cannot be run, fails or takes too long: what is looked up is not there
    private static String run(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return process.exitValue() != 0 ? "" : new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    @Override
    public String toString() {
        return format("PolarisWhitelister[%s %s]", cloud, context);
    }

    /**
     * The parts of the cluster that restrict who can call Polaris, through the Kubernetes API: its ingress, and the service of the
     * nginx controller that serves it.
     */
    private static class Ingress {
        static final String NAMESPACE            = "default";
        static final String NAME                 = "polaris-ingress";
        static final String CONTROLLER_NAMESPACE = "ingress-nginx";
        static final String CONTROLLER_LABEL     = "app.kubernetes.io/component=controller";

        private final ApiClient       apiClient;
        private final NetworkingV1Api networking;
        private final CoreV1Api       core;

        Ingress(ApiClient apiClient) {
            this.apiClient  = apiClient;
            this.networking = new NetworkingV1Api(apiClient);
            this.core       = new CoreV1Api(apiClient);
        }

        boolean exists() {
            try {
                networking.readNamespacedIngress(NAME, NAMESPACE).execute();
                return true;
            } catch (ApiException e) {
                if (e.getCode() == 404) {
                    return false;
                }
                throw failure("read the Polaris ingress", e);
            }
        }

        /**
         * @return the annotation, empty if it is not set, which is not the same as set to nothing
         */
        Optional<String> annotation(String key) {
            try {
                Map<String, String> annotations = networking.readNamespacedIngress(NAME, NAMESPACE).execute().getMetadata()
                                                            .getAnnotations();
                return Optional.ofNullable(annotations == null ? null : annotations.get(key));
            } catch (ApiException e) {
                throw failure("read the Polaris ingress", e);
            }
        }

        // a merge patch sets the annotation, or removes it if null, and leaves the others as they are
        void patchAnnotation(String key, String value) {
            String body = format("{\"metadata\":{\"annotations\":{%s:%s}}}", json(key), value == null ? "null" : json(value));
            try {
                PatchUtils.patch(V1Ingress.class,
                    () -> networking.patchNamespacedIngress(NAME, NAMESPACE, new V1Patch(body)).buildCall(null),
                    V1Patch.PATCH_FORMAT_JSON_MERGE_PATCH, apiClient);
            } catch (ApiException e) {
                throw failure("patch the Polaris ingress", e);
            }
        }

        /**
         * @return the name of the service of the nginx controller, or empty if the cluster has none
         */
        Optional<String> controllerService() {
            try {
                return core.listNamespacedService(CONTROLLER_NAMESPACE).labelSelector(CONTROLLER_LABEL).execute().getItems().stream()
                           .map(service -> service.getMetadata().getName())
                           .filter(name -> !name.contains("admission"))
                           .findFirst();
            } catch (ApiException e) {
                // no such namespace, as on a cluster without the nginx controller
                if (e.getCode() == 404) {
                    return Optional.empty();
                }
                throw failure("list the services of the ingress controller", e);
            }
        }

        String trafficPolicy(String service) {
            try {
                V1Service read = core.readNamespacedService(service, CONTROLLER_NAMESPACE).execute();
                String policy = read.getSpec() == null ? null : read.getSpec().getExternalTrafficPolicy();
                return policy == null ? "" : policy;
            } catch (ApiException e) {
                throw failure("read the ingress controller", e);
            }
        }

        void patchService(String service, String trafficPolicy) {
            String body = format("{\"spec\":{\"externalTrafficPolicy\":%s}}", json(trafficPolicy));
            try {
                PatchUtils.patch(V1Service.class,
                    () -> core.patchNamespacedService(service, CONTROLLER_NAMESPACE, new V1Patch(body)).buildCall(null),
                    V1Patch.PATCH_FORMAT_JSON_MERGE_PATCH, apiClient);
            } catch (ApiException e) {
                throw failure("patch the ingress controller", e);
            }
        }

        private static String json(String value) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }

        private static IllegalStateException failure(String action, ApiException e) {
            return new IllegalStateException(format("Could not %s: %s %s", action, e.getCode(),
                                                    e.getResponseBody() == null ? e.getMessage() : e.getResponseBody()), e);
        }
    }
}
