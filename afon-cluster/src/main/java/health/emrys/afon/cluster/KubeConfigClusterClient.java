package health.emrys.afon.cluster;

import health.emrys.afon.config.AfonPropertyProvider;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import org.slf4j.Logger;

import java.io.FileReader;
import java.io.IOException;

public class KubeConfigClusterClient extends ClusterClient {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(KubeConfigClusterClient.class);

    public KubeConfigClusterClient(AfonPropertyProvider properties) {
        super(properties);
    }

    @Override
    protected ApiClient buildClient(AfonPropertyProvider properties) {
        String kubeConfigPath = System.getProperty("user.home") + "/.kube/config";
        try {
            FileReader reader = new FileReader(kubeConfigPath);
            return ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(reader)).build();
        } catch (IOException e) {
            logger.error("Failed to build client from kube config {}",kubeConfigPath, e);
            throw new RuntimeException("Failed to build client from kube config "+kubeConfigPath, e);
        }
    }

}
