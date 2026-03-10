package health.emrys.afon.infra;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.infra.k8s.ClusterState;
import health.emrys.afon.polaris.catalogs.*;
import health.emrys.afon.polaris.comms.PolarisConfigureMain;
import io.kubernetes.client.openapi.ApiException;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

import static health.emrys.afon.config.AfonPropertiesLoader.add;
import static java.lang.String.format;

/**
 * Configures the AWS Polaris instance so Spark can talk to it.
 * It basically registers the clouds storage locations.
 */
public class ClusterPolarisConfiguration implements AutoCloseable {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(ClusterPolarisConfiguration.class);

    private static final Map<SupportedCloudsEnum, Function<Properties, CloudConfigForPolaris>>
        CLOUD_TO_PROVIDER = Map.of(
        SupportedCloudsEnum.AWS, AwsCloudConfigForPolaris::new,
        SupportedCloudsEnum.AZURE, AzureCloudConfigForPolaris::new,
        SupportedCloudsEnum.GCP, GoogleCloudConfigForPolaris::new,
        SupportedCloudsEnum.AZURE_EXTERNAL, ExternalAzureCloudConfigForPolaris::new
        );

    private final PolarisConfigureMain polarisClient;
    private final ClusterState         state;

    public static void main(String[] args) {
        Properties properties = new AfonPropertiesLoader().getProperties();
        try {
            try (ClusterPolarisConfiguration app = new ClusterPolarisConfiguration(properties)) {
                if (args.length == 0) {
                    app.initializePolaris();
                } else if (args.length == 1) {
                    SupportedCloudsEnum cloud = SupportedCloudsEnum.valueOf(args[0]);
                    app.initialize(cloud, properties);
                } else if (args.length == 2) {
                    SupportedCloudsEnum cloud = SupportedCloudsEnum.valueOf(args[0]);
                    Properties external = new Properties();
                    external.putAll(properties);
                    String credential = args[1];
                    logger.info("Credential: " + credential);
                    app.initialize(cloud, add(credential, external));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initialize(SupportedCloudsEnum cloud, Properties properties) throws IncorrectConfigException, IOException {
        CloudConfigForPolaris cloudConfig = CLOUD_TO_PROVIDER.get(cloud).apply(properties);
        initializePolaris(new CloudConfigForPolaris[]{cloudConfig});
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
        props.putAll(properties);
        return props;
    }

    public void initializePolaris() throws IncorrectConfigException, IOException {
        polarisClient.createAllCatalogs(properties, state.getCredentials());
    }

    public void initializePolaris(CloudConfigForPolaris[] configs) throws IncorrectConfigException, IOException {
        polarisClient.createCatalogs(properties, state.getCredentials(), configs);
    }

    public ClusterState getClusterState() {
        return state;
    }

    @Override
    public void close() throws Exception {
        if (state != null) state.close();
    }
}
