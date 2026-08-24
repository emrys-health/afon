package health.emrys.afon.cluster;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import io.kubernetes.client.openapi.ApiException;
import org.slf4j.Logger;

import static health.emrys.afon.cluster.Defaults.POLARIS_INGRESS_NAME;
import static health.emrys.afon.cluster.Defaults.POLARIS_POD_NAME;

/**
 * Find the Polaris host from the cluster pod.
 */
public class ClusterPolarisFinder implements AutoCloseable {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(ClusterPolarisFinder.class);

    private final ClusterClient client;
    private final String polarisHost;

    public ClusterPolarisFinder(AfonPropertyProvider properties) throws ApiException, IncorrectConfigException  {
        this(new KubeConfigClusterClient(properties));
    }

    public ClusterPolarisFinder(ClusterClient client) throws ApiException, IncorrectConfigException {
        this.client = client;
        logger.info("Polaris pod: {}", client.getUniquePodMatchingName(POLARIS_POD_NAME).getMetadata().getName());
        polarisHost = client.getUniqueIngressFor(POLARIS_INGRESS_NAME);
        logger.info("Polaris host: {}", polarisHost);
    }

    public String getPolarisHost() {
        return polarisHost;
    }

    @Override
    public void close() throws Exception {
        client.close();
    }
}
