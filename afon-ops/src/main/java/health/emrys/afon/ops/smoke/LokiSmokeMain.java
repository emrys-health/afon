package health.emrys.afon.ops.smoke;

import health.emrys.afon.cdk.components.MonitoringComponent;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.ops.kube.KubeClient;
import io.kubernetes.client.PortForward;
import io.kubernetes.client.openapi.models.V1Pod;

import java.util.List;

import static health.emrys.afon.ops.kube.KubeClient.nameOfPod;

public class LokiSmokeMain {

    private final AfonPropertyProvider properties;

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
            try (var portForwardResult = client.portForward(MonitoringComponent.MONITORING_NAMESPACE,
                                                            nameOfPod(lokiPod),
                                                            3100)) {

            }
        }
    }

}
