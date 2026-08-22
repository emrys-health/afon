package health.emrys.afon.ops.smoke;

import health.emrys.afon.cluster.EksK8sClient;
import health.emrys.afon.cluster.GenericK8sClient;
import health.emrys.afon.cluster.K8sClient;
import health.emrys.afon.config.FilePropertyProvider;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1Service;

import java.util.List;

public class KubernetesClientSmoke {

    public void smokeTest(K8sClient client) {
        try {
            for (V1Pod pod : client.listPods()) {
                System.out.printf("Pod: %s, IP: %s%n",
                        pod.getMetadata().getName(),
                        pod.getStatus().getPodIP());
            }
            for (V1Service service : client.listServices()) {
                System.out.println(service.getMetadata().getName());
                List<String> externalIPs = service.getSpec().getExternalIPs();
                for (String externalIP : externalIPs) {
                    System.out.println(externalIP);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        var app = new KubernetesClientSmoke();
        var properties = new FilePropertyProvider();
        app.smokeTest(new EksK8sClient(properties));
        app.smokeTest(new GenericK8sClient(properties));
    }
}
