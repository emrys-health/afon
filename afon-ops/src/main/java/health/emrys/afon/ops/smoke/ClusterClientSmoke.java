package health.emrys.afon.ops.smoke;

import health.emrys.afon.cluster.ClusterClient;
import health.emrys.afon.cluster.KubeConfigClusterClient;
import health.emrys.afon.config.FilePropertyProvider;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1Service;

import java.util.List;

public class ClusterClientSmoke {

    private final ClusterClient client;

    public ClusterClientSmoke(ClusterClient client) {
        this.client = client;
    }

    public void printPods(){
        try {
            for (V1Pod pod : client.listPods()) {
                System.out.printf("Pod: %s, IP: %s%n",
                        pod.getMetadata().getName(),
                        pod.getStatus().getPodIP());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void printServices(){
        try {
            for (V1Service service : client.listServices()) {
                System.out.printf("Service: %s%n", service.getMetadata().getName());
                List<String> externalIPs = service.getSpec().getExternalIPs();
                for (String externalIP : externalIPs) {
                    System.out.printf("ExternalIP: %s%n", externalIP);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        var properties = new FilePropertyProvider();
        var app = new ClusterClientSmoke(new KubeConfigClusterClient(properties));
        app.printPods();
        app.printServices();
    }
}
