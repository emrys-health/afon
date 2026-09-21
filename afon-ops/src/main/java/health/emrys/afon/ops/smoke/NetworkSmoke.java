package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.ops.kube.ExecResult;
import health.emrys.afon.ops.kube.KubeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.cdk.SparkWorkloads.MASTER;
import static health.emrys.afon.config.AfonProperty.ALLOWED_DOMAINS_CSV;

public class NetworkSmoke {

    static final  Logger               logger = LoggerFactory.getLogger(NetworkSmoke.class);
    private final AfonPropertyProvider properties;

    private NetworkSmoke(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    public static void main(String[] args) {
        FilePropertyProvider filePropertyProvider = new FilePropertyProvider();
        try (KubeClient client = new KubeClient(filePropertyProvider)) {
            NetworkSmoke app = new NetworkSmoke(filePropertyProvider);
            List<ExecResult> errors = app.checkNetworkWith(client);
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
        }
    }

    public List<ExecResult> checkNetworkWith(KubeClient client) {
        String[]         pods   = new String[]{"spark-connector-0-driver", MASTER + "-0"};
        String           csv    = properties.getValue(ALLOWED_DOMAINS_CSV);
        List<ExecResult> errors = new ArrayList<>();
        for (String pod : pods) {
            logger.info("Checking pod {}", pod);
            try {
                for (String domain : csv.split(",")) {
                    logger.info("Checking domain {}", domain);
                    String     command = "curl -I --connect-timeout 3 --max-time 3 https://" + domain;
                    ExecResult result  = client.exec(pod, command.split(" "));
                    if (result.exitCode() != 0) {
                        logger.error("Problem running {} on {}", command, pod);
                    }
                    if (!result.stdout().startsWith("HTTP")) {
                        logger.info("Unexpected response. Stdout:\n{}\nStderr:{}", result.stdout(), result.stderr());
                        errors.add(result);
                    }
                }
            } catch (Exception e) {
                logger.error("Could not execute on pod" + pod, e);
            }
        }
        return errors;
    }

}
