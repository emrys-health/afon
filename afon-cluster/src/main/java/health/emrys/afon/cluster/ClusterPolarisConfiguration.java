package health.emrys.afon.cluster;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import io.kubernetes.client.openapi.ApiException;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;
import static java.lang.String.format;

/**
 * Finds the Polaris endpoint from interrogating the Kubernetes cluster.
 * Configures the AWS Polaris instance so Spark can talk to it.
 * It basically registers the clouds storage locations.
 */
public class ClusterPolarisConfiguration extends AbstractPolarisConfiguration implements AutoCloseable {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(ClusterPolarisConfiguration.class);

    private final ClusterState state;

    public ClusterPolarisConfiguration(AfonPropertyProvider properties) throws IncorrectConfigException, IOException, ApiException {
        super(properties);
        state = new ClusterState();
        String endpoint = format("%s:443", state.getPolarisHost());
        this.properties.setValue(POLARIS_ENDPOINT, endpoint);
        logger.info("Calling Polaris endpoint: {}", endpoint);
    }

    protected String getCredentials() {
        return state.getCredentials();
    }

    public Properties getClusterProperties() {
        return properties.getProperties();
    }

    public ClusterState getClusterState() {
        return state;
    }

    @Override
    public void close() throws Exception {
        if (state != null) state.close();
    }
}
