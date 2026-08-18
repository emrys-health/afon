package health.emrys.afon.cluster;

import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import io.kubernetes.client.openapi.ApiException;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Properties;

import static java.lang.String.format;

/**
 * Finds the Polaris endpoint from interrogating the Kubernetes cluster.
 * Configures the AWS Polaris instance so Spark can talk to it.
 * It basically registers the clouds storage locations.
 */
public class ClusterPolarisConfiguration extends AbstractPolarisConfiguration implements AutoCloseable {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(ClusterPolarisConfiguration.class);

    private final ClusterState         state;

    public ClusterPolarisConfiguration() throws IncorrectConfigException, IOException, ApiException {
        super(new FilePropertyProvider().getProperties());
        state           = new ClusterState();
        String endpoint = format("%s:443", state.getPolarisHost());
        logger.info("Calling Polaris endpoint: {}", endpoint);
        properties.setProperty(AfonProperty.POLARIS_ENDPOINT.getName(), endpoint);
    }

    public Properties getClusterProperties() {
        var props = new Properties();
        props.putAll(properties);
        return props;
    }

    protected String getCredentials() {
        return state.getCredentials();
    }

    public ClusterState getClusterState() {
        return state;
    }

    @Override
    public void close() throws Exception {
        if (state != null) state.close();
    }
}
