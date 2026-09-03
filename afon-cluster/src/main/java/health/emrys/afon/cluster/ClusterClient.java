package health.emrys.afon.cluster;


import health.emrys.afon.config.AfonPropertyProvider;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1Service;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;

import java.util.List;

import static health.emrys.afon.config.AfonProperty.CLUSTER_NAME;


public abstract class ClusterClient implements AutoCloseable {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(ClusterClient.class);

    private final String clusterName;
    private final ApiClient apiClient;

    public ClusterClient(AfonPropertyProvider properties) {
        this.clusterName = properties.getValue(CLUSTER_NAME);
        this.apiClient = buildClient(properties);
        Configuration.setDefaultApiClient(apiClient);
    }

    protected abstract ApiClient buildClient(AfonPropertyProvider properties);

    public String getClusterName() {
        return clusterName;
    }

    public List<V1Service> listServices() throws ApiException {
        return new CoreV1Api().listNamespacedService("default").execute().getItems();
    }

    public List<V1Pod> listPods() throws ApiException {
        return new CoreV1Api().listNamespacedPod("default").execute().getItems();
    }

    @Override
    public void close() throws Exception {
        logger.info("Closing Kubernetes client");
        if (apiClient != null && apiClient.getHttpClient() != null) {
            OkHttpClient httpClient = apiClient.getHttpClient();
            if (httpClient.cache() != null) {
                logger.info("Closing cache");
                httpClient.cache().close();
            }
            if (httpClient.connectionPool() != null) {
                logger.info("Closing connection pool");
                httpClient.connectionPool().evictAll();
            }
            Dispatcher dispatcher = httpClient.dispatcher();
            if (dispatcher != null && dispatcher.executorService() != null) {
                logger.info("Closing executor service");
                dispatcher.executorService().shutdown();
            }
        }
        Configuration.setDefaultApiClient(null);
    }

}
