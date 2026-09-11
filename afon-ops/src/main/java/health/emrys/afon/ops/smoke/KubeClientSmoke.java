package health.emrys.afon.ops.smoke;

import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.ops.kube.KubeClient;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1Service;

import java.util.List;

public class KubeClientSmoke {

    private final KubeClient client;

    public KubeClientSmoke(KubeClient client) {
        this.client = client;
    }

    public void printCluster() {
        System.out.printf("Cluster: %s%n",
                client.getClusterName());
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
        var properties = new OpsPropertyProvider();
        var app = new KubeClientSmoke(new KubeClient(properties));
        app.printCluster();
        app.printPods();
        app.printServices();
    }

}
