package health.emrys.afon.infra;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.infra.k8s.ClusterState;
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
public class ClusterPolarisConfiguration {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(ClusterPolarisConfiguration.class);

    public static void main(String[] args) {
        Properties properties = new AfonPropertiesLoader().getProperties();
        var        app        = new ClusterPolarisConfiguration(properties);
        try {
            app.initializePolaris();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private final Properties properties;

    public ClusterPolarisConfiguration(Properties properties) {
        this.properties  = properties;
    }

    public void initializePolaris() throws ApiException, IncorrectConfigException, IOException {
        ClusterState         state         = new ClusterState();
        PolarisConfigureMain polarisClient = new PolarisConfigureMain();
        properties.setProperty(AfonProperties.POLARIS_ENDPOINT.getPropertyName(), format("%s:443", state.getPolarisHost()));
        polarisClient.createAllCatalogs(properties, state.getCredentials());
    }

}
