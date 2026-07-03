package health.emrys.afon.acceptance;

import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.catalogs.AwsCloudConfigForPolaris;
import health.emrys.afon.polaris.catalogs.AzureCloudConfigForPolaris;
import health.emrys.afon.polaris.catalogs.GoogleCloudConfigForPolaris;
import health.emrys.afon.polaris.comms.AfonPolarisClient;
import health.emrys.afon.polaris.comms.AfonPolarisClientFactory;
import health.emrys.afon.polaris.comms.KeyCloakPolarisClient;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.polaris.catalogs.AwsUtils.bucketToUrl;
import static health.emrys.afon.polaris.catalogs.AzureUtils.bucketUrl;

public class AcceptanceTestConfig {

    public static final AcceptanceTestConfig instance;

    static {
        AfonPropertiesLoader loader = new AfonPropertiesLoader();
        instance = new AcceptanceTestConfig(loader.getProperties());
    }

    private final AfonPolarisClientFactory clientFactory = AfonPolarisClientFactory.clientFactory();

    private final AfonPolarisClient           polarisClient;
    private final AwsCloudConfigForPolaris    awsCloudConfigForPolaris;
    private final AwsCloudConfigForPolaris    awsForbidden;
    private final AzureCloudConfigForPolaris  azureCloudConfigForPolaris;
    private final AzureCloudConfigForPolaris  azureForbidden;
    private final GoogleCloudConfigForPolaris gcpCloudConfigForPolaris;
    private final GoogleCloudConfigForPolaris gcpForbidden;
    private final String                      clientId;
    private final String                      clientSecret;
    private final Properties                  properties;
    private final AwsCloudConfigForPolaris    awsTrino;

    private AcceptanceTestConfig(Properties properties) {
        properties.put(KC_HOSTNAME.getPropertyName(), ContainerHelper.KEYCLOAK_HOSTNAME);
        this.clientId     = POLARIS_ID.getValueFrom(properties);
        this.clientSecret = ***REMOVED***
        this.properties   = properties;
        startContainers(properties);
        this.polarisClient              = clientFactory.getPolarisClient(properties);
        this.awsCloudConfigForPolaris   = new AwsCloudConfigForPolaris(properties);
        this.awsTrino                   = new AwsCloudConfigForPolaris(properties) {
            @Override
            public String getBucketUri() {
                return bucketToUrl(AWS_S3_BUCKET_NAME_TRINO.getValueFrom(properties));
            }

            @Override
            public String warehouseName() {
                return SupportedCloudsEnum.AWS_TRINO.toString();
            }
        };
        this.awsForbidden               = new AwsCloudConfigForPolaris(properties) {
            @Override
            public String getBucketUri() {
                return bucketToUrl(AWS_S3_BUCKET_NAME_FORBIDDEN.getValueFrom(properties));
            }

            @Override
            public String warehouseName() {
                return SupportedCloudsEnum.AWS_FORBIDDEN.toString();
            }
        };
        this.gcpForbidden               = new GoogleCloudConfigForPolaris(properties) {
            @Override
            public String warehouseName() {
                return SupportedCloudsEnum.GCP_FORBIDDEN.toString();
            }

            @Override
            public String getBucketUri() {
                return toBucketUri(GCP_BUCKET_NAME_FORBIDDEN.getValueFrom(properties));
            }
        };
        this.gcpCloudConfigForPolaris   = new GoogleCloudConfigForPolaris(properties);
        this.azureCloudConfigForPolaris = new AzureCloudConfigForPolaris(properties);
        this.azureForbidden             = new AzureCloudConfigForPolaris(properties) {
            public String warehouseName() {
                return SupportedCloudsEnum.AZURE_FORBIDDEN.toString();
            }

            @Override
            public String getBucketUri() {
                return bucketUrl(
                    AZURE_BUCKET_FORBIDDEN.getValueFrom(properties),
                    AZURE_STORAGE_ACCOUNT.getValueFrom(properties));
            }
        };
    }

    private static void startContainers(Properties properties) {
        ContainerHelper containerHelper = ContainerHelper.instance;
        containerHelper.startKeyCloak(properties);
        containerHelper.startPolaris(properties);
        containerHelper.startTrino(properties);
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

    public GoogleCloudConfigForPolaris getGcpForbidden() {
        return gcpForbidden;
    }

    public AzureCloudConfigForPolaris getAzureForbidden() {
        return azureForbidden;
    }


    public AwsCloudConfigForPolaris getAwsTrino() {
        return awsTrino;
    }
}
