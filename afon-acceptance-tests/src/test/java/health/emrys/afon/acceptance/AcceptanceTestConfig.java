package health.emrys.afon.acceptance;

import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.catalogs.AwsCloudConfigForPolaris;
import health.emrys.afon.polaris.catalogs.AzureCloudConfigForPolaris;
import health.emrys.afon.polaris.catalogs.GoogleCloudConfigForPolaris;
import health.emrys.afon.polaris.comms.AfonPolarisClient;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;

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
    private final AzureCloudConfigForPolaris  azureCloudConfigForPolaris;
    private final AwsCloudConfigForPolaris    awsForbidden;

    private AcceptanceTestConfig(Properties properties) {
        this.clientId     = POLARIS_ID.getValueFrom(properties);
        this.clientSecret = ***REMOVED***
        this.properties   = properties;
        ContainerHelper.instance.start(properties);
        this.polarisClient              = new AfonPolarisClient(properties);
        this.awsCloudConfigForPolaris   = new AwsCloudConfigForPolaris(properties);
        this.awsForbidden               = new AwsCloudConfigForPolaris(properties, AWS_S3_BUCKET_NAME_FORBIDDEN);
        this.gcpCloudConfigForPolaris   = new GoogleCloudConfigForPolaris(properties);
        this.azureCloudConfigForPolaris = new AzureCloudConfigForPolaris(properties);
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

    public AwsCloudConfigForPolaris getAwsForbidden() {
        return awsForbidden;
    }
}
