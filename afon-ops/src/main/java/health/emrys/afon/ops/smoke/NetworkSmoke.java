package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.ops.kube.ExecResult;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.smoke.tasks.BasicCurl;
import io.kubernetes.client.openapi.ApiException;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static health.emrys.afon.cdk.SparkWorkloads.MASTER;
import static health.emrys.afon.config.AfonProperty.ALLOWED_DOMAINS_CSV;

public class NetworkSmoke {

    static final  Logger               logger = LoggerFactory.getLogger(NetworkSmoke.class);
    private final AfonPropertyProvider properties;
    private final KubeClient           client;

    private NetworkSmoke(AfonPropertyProvider properties, KubeClient client) {
        this.properties = properties;
        this.client     = client;
    }

    public static void main(String[] args) {
        FilePropertyProvider filePropertyProvider = new FilePropertyProvider();
        try (KubeClient client = new KubeClient(filePropertyProvider)) {
            NetworkSmoke     app    = new NetworkSmoke(filePropertyProvider, client);
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
        }
    }

    public List<ExecResult> checkNetworkWith() {
        String[]         pods   = new String[]{"spark-connector-0-driver", MASTER + "-0"};
        List<ExecResult> errors = new ArrayList<>();
        for (String pod : pods) {
            logger.info("Checking pod {}", pod);
            try {
                errors.addAll(checkCanSeeAllowedDomains(pod));
                errors.addAll(checkCannotSeeOtherBuckets(pod));
            } catch (Exception e) {
                logger.error("Could not execute on pod" + pod, e);
            }
        }
        return errors;
    }

    private List<ExecResult> checkCannotSeeOtherBuckets(String pod) throws Exception {
        List<ExecResult> errors = new ArrayList<>();
        String[] providers = new String[] {
            "unreachablebucket.eu-west-2.amazonaws.com",
            "unreachablebucket.blob.core.windows.net",
            "pypi.io",
            "pypi.org",
            "pypi.org",
        };
        for (String provider : providers) {
            BasicCurl command = new BasicCurl(client, pod, provider , false);
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
