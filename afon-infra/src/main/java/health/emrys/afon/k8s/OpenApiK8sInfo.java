package health.emrys.afon.k8s;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.util.Config;

public class OpenApiK8sInfo {
    public static void main(String[] args) throws Exception {
        // Creates a client using kubeconfig (~/.kube/config) or in-cluster config
//        ApiClient client = Config.defaultClient();
        ApiClient client = Config.fromConfig("/home/henryp/Temp/config");

        io.kubernetes.client.openapi.Configuration.setDefaultApiClient(client);

        CoreV1Api api = new CoreV1Api();

        // List Pods in a namespace (e.g., "default")
        var list = api.listNamespacedPod("default");

        for (V1Pod pod : list.execute().getItems()) {
            String podName = pod.getMetadata().getName();
            String podIP = pod.getStatus().getPodIP();
            System.out.printf("Pod: %s, IP: %s%n", podName, podIP);
        }
    }
}
