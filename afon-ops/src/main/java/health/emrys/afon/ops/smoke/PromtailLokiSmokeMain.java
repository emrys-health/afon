package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.kube.tools.JupyterHubSpawner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static health.emrys.afon.ops.smoke.LokiClient.startTimeOf;
import static health.emrys.afon.ops.smoke.LokiClient.withRetries;
import static health.emrys.afon.ops.smoke.config.SmokeConfig.smokeTestProperties;
import static java.lang.String.format;

/**
 * Checks that Promtail ships the logs of a Jupyter pod to Loki.
 */
public class PromtailLokiSmokeMain {

    static final Logger logger = LoggerFactory.getLogger(PromtailLokiSmokeMain.class);

    private final AfonPropertyProvider properties;

    public static void main(String[] args) throws Exception {
        PromtailLokiSmokeMain app = new PromtailLokiSmokeMain(smokeTestProperties());
        try {
            app.checkLoki();
        } catch (Exception e) {
            logger.error(e.getMessage());
            throw e;
        }
    }

    public PromtailLokiSmokeMain(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    private void checkLoki() throws Exception {
        try (KubeClient client = new KubeClient(properties)) {
            String jupyterPod = new JupyterHubSpawner(client).spawnWithJupyterToken(properties);
            logger.info("Started a Jupyter pod called: {}", jupyterPod);
            // Loki only looks back an hour by default, and a pod that has been running a while may not have logged since
            String start = startTimeOf(client, jupyterPod);
            logger.info("Querying Loki for the logs of {} since it started at {}", jupyterPod, start);

            try (LokiClient loki = new LokiClient(client, properties)) {
                withRetries(3, () -> checkPodIsWatched(loki, jupyterPod, start));
                checkPodLogs(loki, jupyterPod, start);
            }
        }
    }

    private static List<String> checkPodIsWatched(LokiClient loki, String jupyterPod, String start) throws Exception {
        List<String> pods = loki.labelValues("pod", start);
        if (!pods.contains(jupyterPod)) {
            throw new RuntimeException(format("%s is not in the watchlist: %s", jupyterPod, pods));
        }
        return pods;
    }

    private static void checkPodLogs(LokiClient loki, String jupyterPod, String start) throws Exception {
        List<String> lines = loki.queryRange(format("{namespace=\"default\", pod=\"%s\"}", jupyterPod), start, 20);
        if (lines.isEmpty()) {
            throw new RuntimeException(format("There are no logs for pod %s", jupyterPod));
        }
        logger.info("lines = {}", String.join("\n", lines));
    }
}
