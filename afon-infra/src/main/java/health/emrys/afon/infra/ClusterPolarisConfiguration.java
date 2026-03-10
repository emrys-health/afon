package health.emrys.afon.infra;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.infra.k8s.ClusterState;
import health.emrys.afon.polaris.catalogs.CloudConfigForPolaris;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.comms.PolarisConfigureMain;
import io.kubernetes.client.openapi.ApiException;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Properties;

import static java.lang.String.format;

/**
 * Configures the AWS Polaris instance so Spark can talk to it.
 * It basically registers the clouds storage locations.
 */
public class ClusterPolarisConfiguration extends AbstractPolarisConfiguration implements AutoCloseable {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(ClusterPolarisConfiguration.class);

    private final PolarisConfigureMain polarisClient;
    private final ClusterState         state;

    public static void main(String[] args) {
        try {
            try (ClusterPolarisConfiguration app = new ClusterPolarisConfiguration()) {
                parse(args, app);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public ClusterPolarisConfiguration() throws IncorrectConfigException, IOException, ApiException {
        super(new AfonPropertiesLoader().getProperties());
        state           = new ClusterState();
        polarisClient   = new PolarisConfigureMain();
        String endpoint = format("%s:443", state.getPolarisHost());
        logger.info("Calling Polaris endpoint: {}", endpoint);
        properties.setProperty(AfonProperties.POLARIS_ENDPOINT.getPropertyName(), endpoint);
    }

    public Properties getClusterProperties() {
        var props = new Properties();
        props.putAll(properties);
        return props;
    }

    public void initializePolaris() throws IncorrectConfigException, IOException {
        polarisClient.createAllCatalogs(properties, getCredentials());
    }

    public void initializePolaris(CloudConfigForPolaris[] configs) throws IncorrectConfigException, IOException {
        polarisClient.createCatalogs(properties, getCredentials(), configs);
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
