package health.emrys.afon.acceptance;

import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.catalogs.AwsCloudConfigForPolaris;
import health.emrys.afon.polaris.catalogs.AzureCloudConfigForPolaris;
import health.emrys.afon.polaris.catalogs.GoogleCloudConfigForPolaris;
import health.emrys.afon.polaris.comms.AfonPolarisClient;
import health.emrys.afon.polaris.comms.PolarisConfigurer;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.POLARIS_ID;
import static health.emrys.afon.config.AfonProperties.POLARIS_SECRET;

public class AcceptanceTestConfig {

    public static final AcceptanceTestConfig instance;

    static {
        AfonPropertiesLoader loader = new AfonPropertiesLoader();
        instance = new AcceptanceTestConfig(loader.getProperties());
    }

    private final AwsCloudConfigForPolaris    awsCloudConfigForPolaris;
    private final AfonPolarisClient           polarisClient;
    private final String                      clientId;
    private final String                      clientSecret;
    private final Properties                  properties;
    private final GoogleCloudConfigForPolaris gcpCloudConfigForPolaris;
    private final AzureCloudConfigForPolaris azureCloudConfigForPolaris;

    private AcceptanceTestConfig(Properties properties) {
        this.clientId     = POLARIS_ID.getValueFrom(properties);
        this.clientSecret = ***REMOVED***
        this.properties   = properties;
        ContainerHelper.instance.start(properties);
        this.polarisClient              = new AfonPolarisClient(properties);
        this.awsCloudConfigForPolaris   = new AwsCloudConfigForPolaris(properties);
        this.gcpCloudConfigForPolaris   = new GoogleCloudConfigForPolaris(properties);
        this.azureCloudConfigForPolaris = new AzureCloudConfigForPolaris(properties);
    }

    /**
     * This initializes Polaris completely.
     */
    public void initializePolaris() {
        try {
            var configurer = new PolarisConfigurer(polarisClient);
            configurer.configurCatalogFor(awsCloudConfigForPolaris, getAccessToken());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Properties getProperties() {
        return new Properties(properties);
    }

    public AfonPolarisClient getPolarisClient() {
        return polarisClient;
    }

    public String getAccessToken() {
        return polarisClient.getAccessToken();
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public AwsCloudConfigForPolaris getAwsCloudConfigForPolaris() {
        return awsCloudConfigForPolaris;
    }

    public GoogleCloudConfigForPolaris getGcpCloudConfigForPolaris() {
        return gcpCloudConfigForPolaris;
    }

    public AzureCloudConfigForPolaris getAzureCloudConfigForPolaris() {
        return azureCloudConfigForPolaris;
    }
}
