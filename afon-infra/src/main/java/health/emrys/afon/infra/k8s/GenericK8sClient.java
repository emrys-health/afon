package health.emrys.afon.infra.k8s;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;

import java.io.FileReader;
import java.io.IOException;

public class GenericK8sClient extends K8sClient {
    public static void main(String[] args) {
        try {
            K8sClient app = new GenericK8sClient();
            app.smokeTest();
        } catch (Exception e) {
            System.err.println("An unexpected error occurred: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public GenericK8sClient() throws IOException {
        String kubeConfigPath = System.getProperty("user.home") + "/.kube/config";
        ApiClient client = ClientBuilder.kubeconfig(
                KubeConfig.loadKubeConfig(new FileReader(kubeConfigPath))
                                                   ).build();
        Configuration.setDefaultApiClient(client);
    }
}
