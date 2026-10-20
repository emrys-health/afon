package health.emrys.afon.ops.smoke;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import health.emrys.afon.cdk.components.MonitoringComponent;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.kube.tools.PortForwarder;
import io.kubernetes.client.openapi.models.V1Pod;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.ops.kube.KubeClient.nameOfPod;

public class LokiSmokeMain {

    static final Logger logger = LoggerFactory.getLogger(NetworkSmokeMain.class);

    private final AfonPropertyProvider properties;

    private static final ObjectMapper MAPPER =
        JsonMapper.builder().enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build();

    public static void main(String[] args) throws Exception {
        AfonPropertyProvider properties = new OpsPropertyProvider();
        LokiSmokeMain app = new LokiSmokeMain(properties);
        app.checkLoki(properties);
    }

    public LokiSmokeMain(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    private void checkLoki(AfonPropertyProvider properties) throws Exception {
        try (KubeClient client = new KubeClient(properties)) {
            List<V1Pod> lokiPods = client.listPodsBeginningWith("loki");
            assert lokiPods.size() == 1;
            V1Pod lokiPod = lokiPods.get(0);
            try (PortForwarder forward = new PortForwarder(client,
                                                           MonitoringComponent.MONITORING_NAMESPACE,
                                                           nameOfPod(lokiPod),
                                                           0,
                                                           3100)) {
                int localPort = forward.localPort();
                logger.info("" + localCurl(localPort));

                /*
  curl -s -H "X-Scope-OrgID: $TENANT" localhost:3100/loki/api/v1/label/pod/values | jq

  # Recent lines for one pod (default lookback is the last hour)
  curl -s -G -H "X-Scope-OrgID: $TENANT" localhost:3100/loki/api/v1/query_range \
    --data-urlencode 'query={namespace="default", pod="spark-connector-0-driver"}' \
    --data-urlencode 'limit=20' | jq -r '.data.result[].values[][1]'

                 */
            }

        }
    }

    private Map localCurl(int port) throws IOException {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(String.format("http://localhost:%d/loki/api/v1/label/pod/values", port));
            request.addHeader("X-Scope-OrgID", "full-emrys-cluster");
            return client.execute(request, response -> {
                return MAPPER.readValue(response.getEntity().getContent(), Map.class);
            });
        }
    }

}
