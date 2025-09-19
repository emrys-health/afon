package health.emrys.afon.acceptance.aws;

import health.emrys.afon.polaris.catalogs.AwsCloudConfigForPolaris;
import health.emrys.afon.polaris.comms.AfonPolarisClient;
import health.emrys.afon.polaris.comms.PolarisConfigurer;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;

public class AcceptanceTestConfig {

    public static final AcceptanceTestConfig instance;

    static {
        Properties properties = new Properties();
        try {
            properties.load(new FileInputStream("../" + PROJECT_PROPERTIES_FILE)); // are we running as a build?
        } catch (IOException e) {
            try {
                properties.load(new FileInputStream(PROJECT_PROPERTIES_FILE)); // ...or in the IDE?
            } catch (IOException ex) {
                throw new RuntimeException(ex); // urgh, the properties file is not there
            }
        }
        instance = new AcceptanceTestConfig(properties);
    }

    private final AwsCloudConfigForPolaris awsCloudConfigForPolaris;
    private final AfonPolarisClient        polarisClient;
    private final String                   clientId;
    private final String                   clientSecret;
    private final Properties               properties;

    private AcceptanceTestConfig(Properties properties) {
        this.clientId                 = POLARIS_ID.getValueFrom(properties);
        this.clientSecret             = ***REMOVED***
        this.properties               = properties;
        this.polarisClient            = new AfonPolarisClient(properties);
        this.awsCloudConfigForPolaris = new AwsCloudConfigForPolaris(properties);
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
}
