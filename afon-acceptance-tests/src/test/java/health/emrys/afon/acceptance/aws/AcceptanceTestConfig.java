package health.emrys.afon.acceptance.aws;

import health.emrys.afon.polaris.catalogs.AwsCloudConfigForPolaris;
import health.emrys.afon.polaris.comms.AfonPolarisClient;
import health.emrys.afon.polaris.comms.PolarisConfigurer;

import java.io.FileInputStream;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;

public class AcceptanceTestConfig {

    public static final AcceptanceTestConfig instance = new AcceptanceTestConfig();

    private final AwsCloudConfigForPolaris awsCloudConfigForPolaris;
    private final AfonPolarisClient        polarisClient;
    private final String                   accessToken;
    private final String                   clientId;
    private final String                   clientSecret;

    private AcceptanceTestConfig() {
        Properties properties = new Properties();
        try {
            properties.load(new FileInputStream("../" + PROJECT_PROPERTIES_FILE));
            this.clientId            = POLARIS_ID.getValueFrom(properties);
            this.clientSecret        = POLARIS_SECRET.getValueFrom(properties);
            polarisClient            = new AfonPolarisClient(properties);
            accessToken              = polarisClient.getAccessToken();
            awsCloudConfigForPolaris = new AwsCloudConfigForPolaris(properties);
            var configurer = new PolarisConfigurer(polarisClient);
            configurer.configurCatalogFor(awsCloudConfigForPolaris, accessToken);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String getAccessToken() {
        return accessToken;
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
