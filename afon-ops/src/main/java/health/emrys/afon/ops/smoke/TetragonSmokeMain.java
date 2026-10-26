package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.kube.ExecResult;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.kube.tools.JupyterHubSpawner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

import static health.emrys.afon.ops.smoke.LokiClient.startTimeOf;
import static health.emrys.afon.ops.smoke.LokiClient.withRetries;
import static health.emrys.afon.ops.smoke.config.SmokeConfig.smokeTestProperties;
import static java.lang.String.format;

/**
 * Checks that Tetragon sees a process run in a Jupyter pod, by running a command with a unique argument there and
 * finding the event of it in the logs of Tetragon's {@code export-stdout} sidecar, which Promtail ships to Loki.
 * See {@code TetragonComponent}.
 */
public class TetragonSmokeMain {

    static final Logger logger = LoggerFactory.getLogger(TetragonSmokeMain.class);

    private final AfonPropertyProvider properties;

    public static void main(String[] args) throws Exception {
        TetragonSmokeMain app = new TetragonSmokeMain(smokeTestProperties());
        try {
            app.checkTetragon();
        } catch (Exception e) {
            logger.error(e.getMessage());
            throw e;
        }
    }

    public TetragonSmokeMain(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    private void checkTetragon() throws Exception {
        try (KubeClient client = new KubeClient(properties)) {
            String jupyterPod = new JupyterHubSpawner(client).spawnWithJupyterToken(properties);
            logger.info("Started a Jupyter pod called: {}", jupyterPod);
            // the pod's clock, rather than ours, so the command necessarily runs after it
            String start = startTimeOf(client, jupyterPod);

            String     marker = "afon-tetragon-smoke-" + UUID.randomUUID();
            ExecResult echo   = client.exec(jupyterPod, "/bin/echo", marker);
            if (!echo.isSuccess()) {
                throw new RuntimeException(format("Could not run the command in %s: %s", jupyterPod, echo));
            }

            try (LokiClient loki = new LokiClient(client, properties)) {
                List<String> events = withRetries(10, () -> eventsOf(loki, jupyterPod, marker, start));
                logger.info("Tetragon events = {}", String.join("\n", events));
            }
        }
    }

    private static List<String> eventsOf(LokiClient loki, String jupyterPod, String marker, String start)
    throws Exception {
        String query = format("{namespace=\"default\", container=\"export-stdout\"} |= \"%s\" | json"
                                  + " | process_exec_process_pod_name=\"%s\"", marker, jupyterPod);
        List<String> events = loki.queryRange(query, start, 20);
        if (events.isEmpty()) {
            throw new RuntimeException(format("No Tetragon event in Loki for '%s' in pod %s", marker, jupyterPod));
        }
        return events;
    }
}
