package health.emrys.afon.infra;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.infra.k8s.ClusterState;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.comms.PolarisConfigureMain;
import io.kubernetes.client.openapi.ApiException;
import org.apache.polaris.core.config.PolarisConfiguration;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Properties;

import static java.lang.String.format;

/**
 * Configures the AWS Polaris instance so Spark can talk to it.
 * It basically registers the clouds storage locations.
 */
public class ClusterPolarisConfiguration implements AutoCloseable {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(ClusterPolarisConfiguration.class);

    private final PolarisConfigureMain polarisClient;
    private final ClusterState         state;

    public static void main(String[] args) {
        Properties properties = new AfonPropertiesLoader().getProperties();
        try {
            try (ClusterPolarisConfiguration app = new ClusterPolarisConfiguration(properties)) {
                app.initializePolaris();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private final Properties properties;

    public ClusterPolarisConfiguration(Properties properties) throws IncorrectConfigException, IOException, ApiException {
        this.properties = properties;
        state           = new ClusterState();
        polarisClient   = new PolarisConfigureMain();
        String endpoint = format("%s:443", state.getPolarisHost());
        logger.info("Calling Polaris endpoint: {}", endpoint);
        properties.setProperty(AfonProperties.POLARIS_ENDPOINT.getPropertyName(), endpoint);
    }

    public Properties getClusterProperties() {
        var props = new Properties();
        properties.entrySet().stream().forEach(entry -> props.setProperty(entry.getKey().toString(), entry.getValue().toString()));
        return props;
    }

    public void initializePolaris() throws ApiException, IncorrectConfigException, IOException {
        polarisClient.createAllCatalogs(properties, state.getCredentials());
    }

    public ClusterState getClusterState() {
        return state;
    }

    @Override
    public void close() throws Exception {
        if (state != null) state.close();
    }
}
