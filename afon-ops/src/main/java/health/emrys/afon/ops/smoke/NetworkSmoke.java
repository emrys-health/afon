package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.ops.kube.ExecResult;
import health.emrys.afon.ops.kube.KubeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static health.emrys.afon.cdk.SparkWorkloads.MASTER;
import static health.emrys.afon.config.AfonProperty.ALLOWED_DOMAINS_CSV;

public class NetworkSmoke {

    static final Logger logger = LoggerFactory.getLogger(NetworkSmoke.class);
    private final AfonPropertyProvider properties;

    private NetworkSmoke(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    public static void main(String[] args) {
        FilePropertyProvider filePropertyProvider = new FilePropertyProvider();
        try (KubeClient client = new KubeClient(filePropertyProvider)) {
            NetworkSmoke app = new NetworkSmoke(filePropertyProvider);
            app.checkNetworkWith(client);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    public void checkNetworkWith(KubeClient client) {
        String[] pods = new String[] { "spark-connector-0-driver", MASTER + "-0"};
        for (String pod : pods) {
            logger.info("Checking pod {}", pod);
            try {
                String csv = properties.getValue(ALLOWED_DOMAINS_CSV);
                for (String domain : csv.split(",")) {
                    logger.info("Checking domain {}", domain);
                    String     command      = "curl -I --connect-timeout 3 --max-time 3 https://" + domain;
                    ExecResult result = client.exec(pod, command.split(" "));
                    if (result.exitCode() != 0) {
                        logger.error("Problem running {} on {}", command, pod);
                    }
                    if (!result.stdout().startsWith("HTTP")) {
                        logger.info("Unexpected response. Stdout:\n{}\nStderr:{}", result.stdout(),  result.stderr());
                    }
                }
            } catch (Exception e) {
                logger.error("Could not execute on pod" + pod, e);
            }
        }


    }

}
