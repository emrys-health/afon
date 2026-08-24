package health.emrys.afon.cluster;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import io.kubernetes.client.openapi.ApiException;
import org.slf4j.Logger;

import static health.emrys.afon.config.AfonProperty.*;
import static java.lang.String.format;

/**
 * Find the Polaris endpoint from the cluster Polaris pod.
 * Configures the Polaris instance so Spark can talk to it.
 */
public class ClusterPolarisConfiguration extends PolarisConfiguration implements AutoCloseable {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(ClusterPolarisConfiguration.class);

    private final ClusterPolarisFinder finder;
    private final String credentials;

    public ClusterPolarisConfiguration(AfonPropertyProvider properties) throws IncorrectConfigException, ApiException {
        super(properties);
        credentials = polarisCredentials(properties);
        finder = new ClusterPolarisFinder(properties);
        String endpoint = format("%s:443", finder.getPolarisHost());
        this.properties.setValue(POLARIS_ENDPOINT, endpoint);
        logger.info("Polaris endpoint: {}", endpoint);
    }

    protected String getCredentials() {
        return credentials;
    }

    @Override
    public void close() throws Exception {
        if (finder != null) finder.close();
    }
}
