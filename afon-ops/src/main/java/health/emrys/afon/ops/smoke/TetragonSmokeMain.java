package health.emrys.afon.ops.smoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.kube.ExecResult;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.kube.tools.JupyterHubSpawner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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

    /**
     * The binary run in the Jupyter pod, as Tetragon reports it: the absolute path that the container exec'd
     */
    static final String BINARY = "/bin/echo";

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
            ExecResult echo   = client.exec(jupyterPod, BINARY, marker);
            if (!echo.isSuccess()) {
                throw new RuntimeException(format("Could not run the command in %s: %s", jupyterPod, echo));
            }

            try (LokiClient loki = new LokiClient(client, properties)) {
                List<JsonNode> events = withRetries(10, () -> execEventsOf(loki, jupyterPod, marker, start));
                for (JsonNode event : events) {
                    checkEvent(event, jupyterPod);
                }
                logger.info("Tetragon events = {}", events);
            }
        }
    }

    /**
     * @return the {@code process_exec} events of the command, the only lines of Tetragon carrying the marker other
     * than those of its {@code process_exit}
     */
    private static List<JsonNode> execEventsOf(LokiClient loki,
                                               String jupyterPod,
                                               String marker,
                                               String start)
    throws Exception {
        String         query  = format("{namespace=\"default\", container=\"export-stdout\"} |= \"%s\"", marker);
        List<String>   lines  = loki.queryRange(query, start, 20);
        List<JsonNode> events = new ArrayList<>();
        for (String line : lines) {
            JsonNode event = MAPPER.readTree(line);
            if (event.has("process_exec")) { // this must be a Tetragon event
                events.add(event);
            }
        }
        if (events.isEmpty()) {
            throw new RuntimeException(format(
                "No Tetragon process_exec event in Loki for '%s' in pod %s. Lines = %s",
                marker, jupyterPod, lines));
        }
        return events;
    }

    private static void checkEvent(JsonNode event,
                                   String jupyterPod) {
        JsonNode process = event.at("/process_exec/process");
        String   pod     = process.at("/pod/name").asText();
        String   binary  = process.at("/binary").asText();
        if (!jupyterPod.equals(pod)) {
            throw new RuntimeException(format(
                "Expected the event of pod %s but it was of '%s': %s",
                jupyterPod, pod, event));
        }
        if (!BINARY.equals(binary)) {
            throw new RuntimeException(format(
                "Expected the event of binary %s but it was of '%s': %s",
                BINARY, binary, event));
        }
    }
}
