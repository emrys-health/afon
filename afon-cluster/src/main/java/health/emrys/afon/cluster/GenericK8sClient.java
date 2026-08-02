package health.emrys.afon.cluster;

import health.emrys.afon.config.AfonPropertiesLoader;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;

import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;

public class GenericK8sClient extends K8sClient implements AutoCloseable {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(GenericK8sClient.class);

    private ApiClient client;

    public GenericK8sClient(){
        this(new AfonPropertiesLoader().getProperties());
    }

    public GenericK8sClient(Properties properties) {
        super(properties);
        configureClient();
    }

    private void configureClient() {
        String kubeConfigPath = System.getProperty("user.home") + "/.kube/config";
        try {
            client = ClientBuilder.kubeconfig(
                    KubeConfig.loadKubeConfig(new FileReader(kubeConfigPath))
            ).build();
            Configuration.setDefaultApiClient(client);
            logger.info("Set default Kubernetes Client");
        } catch (IOException e) {
            throw new RuntimeException("Failed to load kube config from "+kubeConfigPath, e);
        }
    }

    @Override
    public void close() throws Exception {
        logger.info("Closing Kubernetes client");
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

    public static void main(String[] args) {
        K8sClient app = new GenericK8sClient();
        app.smokeTest();
    }
}
