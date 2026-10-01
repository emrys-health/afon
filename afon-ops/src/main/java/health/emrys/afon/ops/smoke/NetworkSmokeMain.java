package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.ops.kube.ExecResult;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.smoke.tasks.BasicCurl;
import health.emrys.afon.polaris.config.PolarisEndpoint;
import health.emrys.afon.polaris.config.PolarisRestClient;
import health.emrys.afon.polaris.config.PolarisRestClientFactory;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.config.AfonProperty.ALLOWED_DOMAINS_CSV;
import static health.emrys.afon.polaris.config.PolarisEndpoint.getPolarisEndpointFor;
import static java.lang.String.format;

public class NetworkSmokeMain {

    static final  Logger               logger = LoggerFactory.getLogger(NetworkSmokeMain.class);
    private final AfonPropertyProvider properties;
    private final KubeClient           client;

    private NetworkSmokeMain(AfonPropertyProvider properties, KubeClient client) {
        this.properties = properties;
        this.client     = client;
    }

    public static void main(String[] args) throws Exception {
        FilePropertyProvider filePropertyProvider = new FilePropertyProvider();
        try (KubeClient client = new KubeClient(filePropertyProvider)) {
            NetworkSmokeMain app    = new NetworkSmokeMain(filePropertyProvider, client);
            List<ExecResult> errors = app.checkNetworkWith();
            if (errors.isEmpty()) {
                logger.info("Network Smoke is OK");
            } else {
                StringBuilder szb = new StringBuilder();
                for (ExecResult error : errors) {
                    szb.append(error).append("\n");
                }
                logger.error(szb.toString());
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw e;
        }
    }

    interface Check {
        List<ExecResult> check(String pod) throws Exception;
    }

    public List<ExecResult> checkNetworkWith() throws Exception {
        List<ExecResult> results = new ArrayList<>();
        results.addAll(checkPolarisOnlyAccessibleFromCluster());
        results.addAll(checkNetworkWith(sparkPods(), List.of(this::checkCanSeeAllowedDomains,
                                                             this::checkCannotSeeOtherBuckets)));
        results.addAll(checkNetworkWith(jupyterPods(), List.of(this::checkCannotSeeOtherBuckets)));
        return results;
    }

    private List<ExecResult> checkPolarisOnlyAccessibleFromCluster() throws Exception {
        SmokeUtils        smokeUtils    = new SmokeUtils(properties);
        AfonCloud         cloud         = smokeUtils.cloudToTest();
        String            polarisHost   = getPolarisEndpointFor(cloud, properties);
        PolarisRestClient polarisClient = PolarisRestClientFactory.clientFactory().createClient(properties, polarisHost);
        List<ExecResult>  results = new ArrayList<>();
        try {
            logger.warn("About to call {}. Note that if whitelisting is turned on, this can take some time. TODO add a timeout",
                        polarisHost);
            HttpResponse response = polarisClient.get("/", "bogus_token");
            int          httpCode = response.getCode(); // probably a 403 or 404 but that's not the point!
            String       message  = format(
                """
                    Managed to make an HTTP call to %s. Received HTTP code %d. 
                    Run this to make Polaris white list the TRE's IP address:
                    
                    kubectl annotate ingress polaris-ingress -n default --overwrite alb.ingress.kubernetes.io/inbound-cidrs=EGRESS_IP/32
                    
                    The configured EGRESS_IP of this cluster is %s
                    
                    If you want to add this machines IP address to that whitelist (danger, Will Robinson), get it via:
                    
                    curl -s https://ifconfig.me
                    
                    and add it to EGRESS_IPs (which are CSVs)
                    """,
                polarisHost,
                httpCode,
                smokeUtils.egressIP());
            results.add(new ExecResult(-1, message, "", format("curl https://%s", polarisHost)));
        } catch (IOException x) {
            String msg = format("Was unable to talk to %s. Error = %s", polarisHost, x.getMessage());
            logger.info(msg);
            results.add(new ExecResult(0, msg, "", format("curl https://%s", polarisHost)));
        }
        return results;
    }

    public List<ExecResult> checkNetworkWith(List<String> pods, List<Check> checks) throws Exception {
        List<ExecResult> errors = new ArrayList<>();
        for (String pod : pods) {
            logger.info("Checking pod {}", pod);
            try {
                for (Check check : checks) {
                    errors.addAll(check.check(pod));
                }
            } catch (Exception e) {
                logger.error("Could not execute on pod" + pod, e);
            }
        }
        return errors;
    }

    protected List<String> sparkPods() throws Exception {
        return client.listPods().stream().map(x -> x.getMetadata().getName()).filter(name -> {
            return //name.contains(MASTER) ||name.contains("emrys-worker") || // don't need to worry about Spark as need the connector to upload JARS
                name.contains("spark-connector");
        }).toList();
    }

    protected List<String> jupyterPods() throws Exception {
        List<String> pods = new ArrayList<>();
        pods.add(new JupyterHubSpawner(client).spawnWithJupyterToken(properties));
        logger.info("Will check pods: {}", pods);
        return pods;
    }

    private List<ExecResult> checkCannotSeeOtherBuckets(String pod) throws Exception {
        List<ExecResult> errors = new ArrayList<>();
        String[] providers = new String[]{
            "unreachablebucket.eu-west-2.amazonaws.com",
            "unreachablebucket.blob.core.windows.net",
            "pypi.io",
            "pypi.org",
            "pypi.org",
            };
        for (String provider : providers) {
            BasicCurl command = new BasicCurl(client, pod, provider, false);
            errors.addAll(command.run());
        }
        return errors;
    }

    private List<ExecResult> checkCanSeeAllowedDomains(String pod) throws Exception {
        List<ExecResult> errors = new ArrayList<>();
        String           csv    = properties.getValue(ALLOWED_DOMAINS_CSV);
        for (String domain : csv.split(",")) {
            BasicCurl command = new BasicCurl(client, pod, domain, true);
            errors.addAll(command.run());
        }
        return errors;
    }

}
