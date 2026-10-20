package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.ops.kube.ExecResult;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.kube.commands.CurlCommand;
import health.emrys.afon.ops.kube.tools.JupyterHubSpawner;
import health.emrys.afon.ops.smoke.config.SmokeConfig;
import health.emrys.afon.polaris.config.PolarisRestClient;
import health.emrys.afon.rest.HttpResponses;
import health.emrys.afon.polaris.config.PolarisRestClientFactory;
import org.apache.hc.core5.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static health.emrys.afon.polaris.config.PolarisEndpoint.getPolarisEndpointFor;
import static java.lang.String.format;

public class NetworkSmokeMain {

    static final  Logger               logger = LoggerFactory.getLogger(NetworkSmokeMain.class);
    private final AfonPropertyProvider properties;
    private final KubeClient client;

    private NetworkSmokeMain(AfonPropertyProvider properties, KubeClient client) {
        this.properties = properties;
        this.client     = client;
    }

    interface Check {
        List<ExecResult> check(String pod) throws Exception;
    }

    public List<ExecResult> checkNetworkWithPods() throws Exception {
        List<ExecResult> results = new ArrayList<>();
        results.addAll(checkPolarisOnlyAccessibleFromCluster());
        results.addAll(checkNetworkWithPods(sparkPods(), List.of(this::checkCanSeeAllowedDomains,
                                                             this::checkCannotSeeOtherBuckets)));
        results.addAll(checkNetworkWithPods(jupyterPods(), List.of(this::checkCannotSeeOtherBuckets)));
        return results;
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
        SmokeConfig smokeConfig = new SmokeConfig(properties);
        AfonCloud         cloud         = smokeConfig.cloud();
        String            polarisHost   = getPolarisEndpointFor(cloud, properties);
        PolarisRestClient polarisClient = PolarisRestClientFactory.clientFactory().createClient(properties, polarisHost);
        List<ExecResult>  results = new ArrayList<>();
        try {
            logger.warn("About to call {}. Note that if whitelisting is turned on, this can take up to {}s",
                        polarisHost, smokeConfig.callTimeoutSeconds());
            HttpResponse response = getWithProgress(polarisClient, polarisHost, smokeConfig);
            int          httpCode = response.getCode(); // probably a 403 or 404 but that's not the point!
            if (smokeConfig.rejectedByIngress(httpCode, HttpResponses.contentOf(response))) {
                String msg = format("The ingress of %s rejected the call with HTTP code %d, as it should", polarisHost, httpCode);
                logger.info(msg);
                results.add(new ExecResult(0, msg, "", format("curl https://%s", polarisHost)));
                return results;
            }
            String       message  = format("Managed to make an HTTP call to %s. Received HTTP code %d.%n%n%s",
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
            long start = System.nanoTime();
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
            results.addAll(command.run());
        }
        return results;
    }

    public static void main(String[] args) throws Exception {
        int exitCode = 0;
        AfonPropertyProvider properties = new FilePropertyProvider(); // please do not change this
        try (KubeClient client = new KubeClient(properties)) {
            NetworkSmokeMain app    = new NetworkSmokeMain(properties, client);
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
