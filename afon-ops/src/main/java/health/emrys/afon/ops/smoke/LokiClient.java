package health.emrys.afon.ops.smoke;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import health.emrys.afon.cdk.components.MonitoringComponent;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.kube.tools.PortForwarder;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1Pod;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static health.emrys.afon.config.AfonProperty.CLUSTER_NAME;
import static health.emrys.afon.ops.kube.KubeClient.nameOfPod;
import static java.lang.String.format;

/**
 * Queries the Loki of the {@link MonitoringComponent} through a port forward to its pod, as the tenant that Promtail
 * pushes the logs of the cluster as.
 */
public class LokiClient implements AutoCloseable {

    static final Logger logger = LoggerFactory.getLogger(LokiClient.class);

    private static final ObjectMapper MAPPER =
        JsonMapper.builder().enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build();

    private final String        tenantId;
    private final PortForwarder forward;

    public LokiClient(KubeClient client, AfonPropertyProvider properties) throws ApiException, IOException {
        this.tenantId = "full-" + properties.getValue(CLUSTER_NAME); // I guess it always must be full(?)
        this.forward  = new PortForwarder(client,
                                          MonitoringComponent.MONITORING_NAMESPACE,
                                          nameOfPod(getLokiPod(client)),
                                          0,
                                          3100);
    }

    /**
     * @return the values Loki has for the label since the start, eg the names of the pods it has logs of
     * @param label eg, "pod"
     */
    public List<String> labelValues(String label, OffsetDateTime start) throws IOException {
        String url = format("http://localhost:%d/loki/api/v1/label/%s/values?start=%s",
                            forward.localPort(),
                            label,
                            URLEncoder.encode(start.toInstant().toString(),
                                              StandardCharsets.UTF_8));
        Map result = localCurl(new HttpGet(url));
        if (!"success".equals(result.get("status"))) {
            throw new RuntimeException(format("Could not reach Loki on %s. Result = %s", url, result));
        }
        return (List<String>) result.get("data");
    }

    /**
     * @return the lines of all the streams matching the LogQL query since the start, without their timestamps
     */
    public List<String> queryRange(String query, OffsetDateTime start, int limit) throws IOException {
        HttpPost request = new HttpPost(format("http://localhost:%d/loki/api/v1/query_range", forward.localPort()));
        request.setEntity(new UrlEncodedFormEntity(
            List.of(new BasicNameValuePair("query", query),
                    new BasicNameValuePair("start", start.toInstant().toString()),
                    new BasicNameValuePair("limit", String.valueOf(limit))),
            StandardCharsets.UTF_8
        ));
        Map logs = localCurl(request);
        Map data = (Map) logs.get("data");
        if (data == null) {
            throw new RuntimeException(format("Loki could not run %s. Result = %s", query, logs));
        }
        List<Map> results = (List<Map>) data.get("result");
        // each value is a [timestamp, line] pair
        return results.stream()
                      .flatMap(x -> ((List<List<String>>) x.get("values")).stream())
                      .map(x -> x.get(1))
                      .toList();
    }

    /**
     * Promtail ships the logs every few seconds, so what was just logged may not be in Loki yet.
     */
    public static <T> T withRetries(int numRetries, Callable<T> check) throws Exception {
        for (int i = 1; ; i++) {
            try {
                return check.call();
            } catch (Exception e) {
                if (i >= numRetries) {
                    throw e;
                }
                logger.warn("Backing off before retry {} of {}: {}", i + 1, numRetries, e.getMessage());
                Thread.sleep(i * 1000L);
            }
        }
    }

    /**
     * @return the start time of the pod as RFC3339, which Loki accepts as a query's start, falling back to its creation
     * time if it has not started yet
     */
    public static OffsetDateTime startTimeOf(KubeClient client, String podName) throws ApiException {
        V1Pod pod = client.listPodsBeginningWith(podName)
                          .stream()
                          .filter(x -> podName.equals(nameOfPod(x)))
                          .findFirst()
                          .orElseThrow(() -> new RuntimeException(format("No pod called %s", podName)));
        OffsetDateTime started = pod.getStatus() != null && pod.getStatus().getStartTime() != null
                                 ? pod.getStatus().getStartTime()
                                 : pod.getMetadata().getCreationTimestamp();
        return started;
    }

    private static V1Pod getLokiPod(KubeClient client) throws ApiException {
        String      prefix   = "loki";
        List<V1Pod> lokiPods = client.listPodsBeginningWith(prefix);
        if (lokiPods.size() != 1) {
            throw new RuntimeException(format("Expected one pod starting '%s' but found %d", prefix, lokiPods.size()));
        }
        return lokiPods.get(0);
    }

    private Map localCurl(HttpUriRequestBase request) throws IOException {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            request.addHeader("X-Scope-OrgID", tenantId);
            return client.execute(request, response -> {
                return MAPPER.readValue(response.getEntity().getContent(), Map.class);
            });
        }
    }

    @Override
    public void close() {
        forward.close();
    }
}
