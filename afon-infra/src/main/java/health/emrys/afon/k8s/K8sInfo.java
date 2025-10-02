package health.emrys.afon.k8s;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;

public class K8sInfo {
    public static void main(String[] args) {
        try (KubernetesClient client = new DefaultKubernetesClient()) {
            PodList pods = client.pods().inNamespace("default").list();

            for (Pod pod : pods.getItems()) {
                String podName = pod.getMetadata().getName();
                String podIP = pod.getStatus().getPodIP();
                System.out.printf("Pod: %s, IP: %s%n", podName, podIP);
            }
        }
    }
}