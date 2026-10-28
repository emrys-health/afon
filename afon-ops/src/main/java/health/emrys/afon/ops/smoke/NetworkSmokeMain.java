package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.kube.ExecResult;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.kube.commands.CurlCommand;
import health.emrys.afon.ops.kube.tools.JupyterHubSpawner;
import health.emrys.afon.ops.smoke.config.SmokeConfig;
import health.emrys.afon.polaris.config.PolarisRestClient;
import health.emrys.afon.rest.HttpResponses;
import health.emrys.afon.polaris.config.PolarisRestClientFactory;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1Ingress;
import io.kubernetes.client.openapi.models.V1IngressRule;
import io.kubernetes.client.openapi.models.V1LoadBalancerIngress;
import io.kubernetes.client.openapi.models.V1Service;
import org.apache.hc.core5.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.ops.smoke.config.SmokeConfig.smokeTestProperties;
import static health.emrys.afon.polaris.config.PolarisEndpoint.getPolarisEndpointFor;
import static java.lang.String.format;

public class NetworkSmokeMain {

    static final  Logger               logger = LoggerFactory.getLogger(NetworkSmokeMain.class);
    private final AfonPropertyProvider properties;
    private final KubeClient           client;
    private final Set<String>          allowedIngressHosts;

    private NetworkSmokeMain(AfonPropertyProvider properties, KubeClient client) {
        this.properties          = properties;
        this.client              = client;
        this.allowedIngressHosts = Set.of(
                                          AWS_JUPYTER_DOMAIN_NAME, AZURE_JUPYTER_DOMAIN_NAME, GCP_JUPYTER_DOMAIN_NAME,
                                          AWS_OIDC_DOMAIN_NAME, AZURE_OIDC_DOMAIN_NAME, GCP_OIDC_DOMAIN_NAME,
                                          AWS_POLARIS_DOMAIN_NAME, AZURE_POLARIS_DOMAIN_NAME, GCP_POLARIS_DOMAIN_NAME)
                                      .stream()
                                      .map(x -> properties.getValue(x, ""))
                                      .filter(x -> !x.isEmpty())
                                      .collect(Collectors.toUnmodifiableSet());
    }

    interface Check {
        List<ExecResult> check(String pod) throws Exception;
    }

    public List<ExecResult> checkNetworkWithPods() throws Exception {
        List<ExecResult> results = new ArrayList<>();
        results.addAll(checkPublicFacingInterfaces());
        results.addAll(checkPolarisOnlyAccessibleFromCluster());
        results.addAll(checkNetworkWithPods(
            sparkPods(), List.of(
                this::checkCanSeeAllowedDomains,
                this::checkCannotSeeOtherBuckets)));
        results.addAll(checkNetworkWithPods(jupyterPods(), List.of(this::checkCannotSeeOtherBuckets)));
        return results;
    }


    /**
     * Stands for every host, which an ingress serves when a rule of it names none, or when it has no rules.
     */
    static final String ANY_HOST = "*";

    /**
     * Checks that no service of the cluster has an EXTERNAL-IP, as {@code kubectl get services} shows it, ie that none
     * is exposed outside the cluster other than through an ingress, and that the ingresses serve only
     * {@link #allowedIngressHosts}.
     */
    private List<ExecResult> checkPublicFacingInterfaces() throws ApiException {
        List<V1Service> services = client.listServices();
        logger.info("Checking services have no EXTERNAL-IP: {}", services.stream().map(s -> s.getMetadata().getName()).toList());
        List<ExecResult> results   = new ArrayList<>(publicFacingInterfaces(services));
        List<V1Ingress>  ingresses = client.listIngresses();
        logger.info(
            "Checking ingresses serve only {}: {}", allowedIngressHosts,
            ingresses.stream().map(i -> i.getMetadata().getName()).toList());
        results.addAll(ingressHosts(ingresses, allowedIngressHosts));
        return results;
    }

    /**
     * @return for each ingress, a failure if it serves a host that is not allowed, else a success
     */
    static List<ExecResult> ingressHosts(List<V1Ingress> ingresses, Set<String> allowed) {
        return ingresses.stream().map(ingress -> {
            String       name       = ingress.getMetadata().getNamespace() + "/" + ingress.getMetadata().getName();
            List<String> notAllowed = hostsOf(ingress).stream().filter(host -> !allowed.contains(host)).toList();
            String       command   = format(
                "kubectl get ingress -n %s %s", ingress.getMetadata().getNamespace(),
                ingress.getMetadata().getName());
            return notAllowed.isEmpty()
                   ? new ExecResult(0, format("Ingress %s serves only allowed hosts", name), "", command)
                   : new ExecResult(
                -1, "", format(
                "Ingress %s serves hosts that are not allowed: %s",
                name, String.join(",", notAllowed)), command);
        }).toList();
    }

    /**
     * @return the hosts of the ingress' rules, {@link #ANY_HOST} for a rule without one, or for an ingress without rules
     */
    static List<String> hostsOf(V1Ingress ingress) {
        List<V1IngressRule> rules = ingress.getSpec() == null ? null : ingress.getSpec().getRules();
        if (rules == null || rules.isEmpty()) {
            return List.of(ANY_HOST);
        }
        return rules.stream()
                    .map(rule -> rule.getHost() == null || rule.getHost().isBlank() ? ANY_HOST : rule.getHost())
                    .distinct()
                    .toList();
    }

    /**
     * @return for each service, a failure if it has an EXTERNAL-IP, else a success
     */
    static List<ExecResult> publicFacingInterfaces(List<V1Service> services) {
        return services.stream().map(service -> {
            String       name     = service.getMetadata().getName();
            List<String> external = externalIps(service);
            String       command  = format("kubectl get service %s", name);
            return external.isEmpty()
                   ? new ExecResult(0, format("Service %s has no EXTERNAL-IP", name), "", command)
                   : new ExecResult(-1, "", format("Service %s has EXTERNAL-IP %s", name, String.join(",", external)), command);
        }).toList();
    }

    /**
     * What {@code kubectl} shows as a service's EXTERNAL-IP: the addresses, or host names, of its load balancer, and any
     * external IPs it is given.  A load balancer still {@code <pending>} has none yet.
     */
    static List<String> externalIps(V1Service service) {
        List<String> ips = new ArrayList<>();
        if (service.getStatus() != null && service.getStatus().getLoadBalancer() != null
            && service.getStatus().getLoadBalancer().getIngress() != null) {
            for (V1LoadBalancerIngress ingress : service.getStatus().getLoadBalancer().getIngress()) {
                if (ingress.getIp() != null && !ingress.getIp().isBlank()) {
                    ips.add(ingress.getIp());
                }
                if (ingress.getHostname() != null && !ingress.getHostname().isBlank()) {
                    ips.add(ingress.getHostname());
                }
            }
        }
        if (service.getSpec() != null && service.getSpec().getExternalIPs() != null) {
            ips.addAll(service.getSpec().getExternalIPs());
        }
        return ips;
    }

    /**
     * Calls Polaris from outside the cluster, which must not get through.  How a caller outside of the allowed CIDRs is
     * stopped differs per cloud:
     * <ul>
     *   <li>AWS: the ALB's security group drops its packets, so the call fails or times out, and any HTTP response
     *   means Polaris is reachable.</li>
     *   <li>Azure: the nginx ingress controller, which runs in the cluster behind a public load balancer, accepts the
     *   connection and answers with its own 403 page, without calling Polaris, see
     *   {@link SmokeConfig#rejectedByIngress}.  That counts as stopped; any other response means Polaris is reachable.</li>
     * </ul>
     */
    private List<ExecResult> checkPolarisOnlyAccessibleFromCluster() throws Exception {
        SmokeConfig       smokeConfig   = new SmokeConfig(properties);
        AfonCloud         cloud         = smokeConfig.cloud();
        String            polarisHost   = getPolarisEndpointFor(cloud, properties);
        PolarisRestClient polarisClient = PolarisRestClientFactory.clientFactory().createClient(properties.forCloud(cloud), polarisHost);
        List<ExecResult>  results       = new ArrayList<>();
        try {
            logger.warn(
                "About to call {}. Note that if whitelisting is turned on, this can take up to {}s",
                polarisHost, smokeConfig.callTimeoutSeconds());
            HttpResponse response = getWithProgress(polarisClient, polarisHost, smokeConfig);
            int          httpCode = response.getCode(); // probably a 403 or 404 but that's not the point!
            if (smokeConfig.rejectedByIngress(httpCode, HttpResponses.contentOf(response))) {
                String msg = format("The ingress of %s rejected the call with HTTP code %d, as it should", polarisHost, httpCode);
                logger.info(msg);
                results.add(new ExecResult(0, msg, "", format("curl https://%s", polarisHost)));
                return results;
            }
            String message = format(
                "Managed to make an HTTP call to %s. Received HTTP code %d.%n%n%s",
                polarisHost, httpCode, smokeConfig.polarisRestrictionAdvice());
            results.add(new ExecResult(-1, message, "", format("curl https://%s", polarisHost)));
        } catch (IOException x) {
            String msg = format("Was unable to talk to %s. Error = %s", polarisHost, x.getMessage());
            logger.info(msg);
            results.add(new ExecResult(0, msg, "", format("curl https://%s", polarisHost)));
        }
        return results;
    }

    /**
     * Calls Polaris' root, logging every {@link SmokeConfig#progressIntervalSeconds()} that it is still waiting for a
     * response, as a call from outside the cluster to a restricted Polaris only fails once it times out, and giving up
     * after {@link SmokeConfig#callTimeoutSeconds()}, rather than after the system's own connect timeout, eg 75s per
     * address on macOS.
     *
     * @throws IOException if the call fails, eg as the connection timed out, or gets no response in time
     */
    private HttpResponse getWithProgress(PolarisRestClient polarisClient, String polarisHost, SmokeConfig smokeConfig)
    throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<HttpResponse> response = executor.submit(() -> polarisClient.get("/", "bogus_token"));
            long                 start    = System.nanoTime();
            while (true) {
                try {
                    return response.get(smokeConfig.progressIntervalSeconds(), TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    long elapsed = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
                    if (elapsed >= smokeConfig.callTimeoutSeconds()) {
                        response.cancel(true);
                        throw new SocketTimeoutException(format("No response within %ds", elapsed));
                    }
                    logger.info("Still waiting for a response from {} after {}s", polarisHost, elapsed);
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof IOException cause) {
                        throw cause;
                    }
                    throw e;
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private List<ExecResult> checkNetworkWithPods(List<String> pods, List<Check> checks) throws Exception {
        List<ExecResult> results = new ArrayList<>();
        for (String pod : pods) {
            logger.info("Checking pod {}", pod);
            try {
                for (Check check : checks) {
                    results.addAll(check.check(pod));
                }
            } catch (Exception e) {
                logger.error("Could not execute on pod " + pod, e);
                results.add(new ExecResult(-1, "", e.getMessage(), format("checks on pod %s", pod)));
            }
        }
        return results;
    }

    protected List<String> sparkPods() throws Exception {
        List<String> pods = client.listPods().stream().map(x -> x.getMetadata().getName()).filter(name -> {
            return //name.contains(MASTER) ||name.contains("emrys-worker") || // don't need to worry about Spark as need the connector to upload JARS
                name.contains("spark-connector");
        }).toList();
        logger.info("Checking Spark pods: {}", pods);
        return pods;
    }

    protected List<String> jupyterPods() throws Exception {
        List<String> pods = new ArrayList<>();
        pods.add(new JupyterHubSpawner(client).spawnWithJupyterToken(properties));
        logger.info("Checking Jupyter pods: {}", pods);
        return pods;
    }

    private List<ExecResult> checkCannotSeeOtherBuckets(String pod) throws Exception {
        List<ExecResult> results = new ArrayList<>();
        String[] providers = new String[]{
            "unreachablebucket.eu-west-2.amazonaws.com",
            "unreachablebucket.blob.core.windows.net",
            "pypi.io",
            "pypi.org",
            "pypi.org",
            };
        for (String provider : providers) {
            CurlCommand command = new CurlCommand(client, pod, provider, false);
            results.addAll(command.run());
        }
        return results;
    }

    private List<ExecResult> checkCanSeeAllowedDomains(String pod) throws Exception {
        List<ExecResult> results = new ArrayList<>();
        for (String domain : new SmokeConfig(properties).allowedDomains()) {
            CurlCommand command = new CurlCommand(client, pod, domain, true);
            results.addAll(leavingOutDownDomain(domain, command.run(), NetworkSmokeMain::resolvesHere));
        }
        return results;
    }

    /**
     * A domain being allowed means that the pod may reach it, not that it answers: it may be down, or not deployed, eg the Keycloak of
     * a cloud other than that of the stack. A pod that gets no response from a domain that this machine cannot resolve either is not
     * stopped by the network policy, so that is not a failure; it is reported as a check not made. Where this machine does resolve
     * it, the pod not getting a response is a failure.
     *
     * @param results  the results of checking that the pod can reach the domain
     * @param resolves whether this machine can resolve the domain
     */
    static List<ExecResult> leavingOutDownDomain(String domain, List<ExecResult> results, Predicate<String> resolves) {
        if (results.stream().allMatch(ExecResult::isSuccess) || resolves.test(domain)) {
            return results;
        }
        logger.warn(
            "Not checking that the pod can reach {}: it does not resolve from here either, so it may be down or not deployed",
            domain);
        return results.stream()
                      .map(result -> result.isSuccess() ? result
                                                        : new ExecResult(
                          0, "Not checked: " + domain + " does not resolve from here either",
                          "", result.command()))
                      .toList();
    }

    private static boolean resolvesHere(String domain) {
        try {
            InetAddress.getByName(domain);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        int                  exitCode   = 0;
        AfonPropertyProvider properties = smokeTestProperties();
        try (KubeClient client = new KubeClient(properties)) {
            NetworkSmokeMain app      = new NetworkSmokeMain(properties, client);
            List<ExecResult> results  = app.checkNetworkWithPods();
            List<ExecResult> failures = results.stream().filter(result -> !result.isSuccess()).toList();
            if (!failures.isEmpty()) {
                logger.error("{} of {} network checks failed:\n{}", failures.size(), results.size(), ExecResult.join(failures));
                exitCode = 1;
            } else {
                logger.info("Network smoke test passed: {} checks", results.size());
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            exitCode = 1;
        }
        System.exit(exitCode);
    }

}
