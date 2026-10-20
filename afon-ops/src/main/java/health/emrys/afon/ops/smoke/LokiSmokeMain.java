package health.emrys.afon.ops.smoke;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import health.emrys.afon.cdk.components.MonitoringComponent;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.kube.tools.JupyterHubSpawner;
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
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static health.emrys.afon.config.AfonProperty.CLUSTER_NAME;
import static health.emrys.afon.ops.kube.KubeClient.nameOfPod;
import static java.lang.String.format;

public class LokiSmokeMain {

    static final Logger logger = LoggerFactory.getLogger(NetworkSmokeMain.class);

    private final AfonPropertyProvider properties;
    private final String               tenantId;

    private static final ObjectMapper MAPPER =
        JsonMapper.builder().enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build();

    public static void main(String[] args) throws Exception {
        AfonPropertyProvider properties = new OpsPropertyProvider();
        LokiSmokeMain        app        = new LokiSmokeMain(properties);
        try {
            app.checkLoki(properties);
        } catch (Exception e) {
            logger.error(e.getMessage());
            throw e;
        }
    }

    public LokiSmokeMain(AfonPropertyProvider properties) {
        this.properties = properties;
        this.tenantId   = "full-" + properties.getValue(CLUSTER_NAME); // I guess it always must be full(?)
    }

    private void checkLoki(AfonPropertyProvider properties) throws Exception {
        try (KubeClient client = new KubeClient(properties)) {
            V1Pod lokiPod = getLokiPod(client);

            String jupyterPod = new JupyterHubSpawner(client).spawnWithJupyterToken(properties);
            logger.info("Started a Jupyter pod called: {}", jupyterPod);

            try (PortForwarder forward = new PortForwarder(client,
                                                           MonitoringComponent.MONITORING_NAMESPACE,
                                                           nameOfPod(lokiPod),
                                                           0,
                                                           3100)) {
                int localPort = forward.localPort();

                checkPodIsWatched(localPort, jupyterPod);

                checkPodLogs(localPort, jupyterPod);
            }

        }
    }

    private void checkPodLogs(int localPort, String jupyterPod) throws IOException {
        HttpPost request = new HttpPost(format("http://localhost:%d/loki/api/v1/query_range", localPort));

        request.setEntity(new UrlEncodedFormEntity(
            List.of(new BasicNameValuePair("query",
                                           format("{namespace=\"default\", pod=\"%s\"}", jupyterPod)),
                    new BasicNameValuePair("limit", "20")),
            StandardCharsets.UTF_8
        ));
        Map            logs       = localCurl(request);
        Stream<String> lineStream = parseLoki(logs);
        List<String>   lineList   = lineStream.toList();
        if (lineList.isEmpty()) {
            throw new RuntimeException(format("There are no logs for pod %s", jupyterPod));
        }
        String lineStr = lineList.stream().collect(Collectors.joining("\n"));
        logger.info("lines = {}", lineStr);
    }

    private static @NonNull Stream<String> parseLoki(Map logs) {
        Map        data    = (Map) logs.get("data");
        List<List> results = (List) data.get("result");
        List       values  = (List) ((Map) results.get(0)).get("values");
        Stream<String> lineStream = values.stream()
                                          .flatMap(x -> ((List<String>) x).stream());
        return lineStream;
    }

    private void checkPodIsWatched(int localPort, String jupyterPod) throws IOException {
        String  url     = format("http://localhost:%d/loki/api/v1/label/pod/values", localPort);
        HttpGet request = new HttpGet(url);
        Map     watched = localCurl(request);
        if (!"success".equals(watched.get("status"))) {
            throw new RuntimeException(format("Could not reach Loki on %s. Result = %s",
                                              url,
                                              watched));
        }
        List pods = (List) watched.get("data");
        if (!pods.stream().anyMatch(x -> jupyterPod.equals(x))) {
            throw new RuntimeException(format("%s is not in the watchlist: %s", jupyterPod, pods));
        }
    }

    private static V1Pod getLokiPod(KubeClient client) throws ApiException {
        String      prefix   = "loki";
        List<V1Pod> lokiPods = client.listPodsBeginningWith(prefix);
        if (lokiPods.size() != 1) {
            throw new RuntimeException(format("No pod starting '%s'", prefix));
        }
        V1Pod lokiPod = lokiPods.get(0);
        return lokiPod;
    }

    private Map localCurl(HttpUriRequestBase request) throws IOException {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            request.addHeader("X-Scope-OrgID", tenantId);
            return client.execute(request, response -> {
                return MAPPER.readValue(response.getEntity().getContent(), Map.class);
            });
        }
    }

}
