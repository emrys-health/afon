package health.emrys.afon.cluster;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;

import java.io.FileReader;
import java.io.IOException;

public class GenericK8sClient extends K8sClient implements AutoCloseable {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(GenericK8sClient.class);

    private final ApiClient client;

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
        client = ClientBuilder.kubeconfig(
                KubeConfig.loadKubeConfig(new FileReader(kubeConfigPath))
                                                   ).build();
        Configuration.setDefaultApiClient(client);
    }

    @Override
    public void close() throws Exception {
        logger.info("Closing Kubernetes Client");
        if (client != null && client.getHttpClient() != null) {
            OkHttpClient httpClient = client.getHttpClient();
            if (httpClient.cache() != null) {
                logger.info("Closing cache");
                httpClient.cache().close();
            }
            if (httpClient.connectionPool() != null) {
                logger.info("Closing connection pool");
                httpClient.connectionPool().evictAll();
            }
            Dispatcher dispatcher = httpClient.dispatcher();
            if (dispatcher != null) {
                if (dispatcher.executorService() != null) {
                    logger.info("Closing executor service");
                    dispatcher.executorService().shutdown();
                }
            }
        }
        Configuration.setDefaultApiClient(null);
    }
}
