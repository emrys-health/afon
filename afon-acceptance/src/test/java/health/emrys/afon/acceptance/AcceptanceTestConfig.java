package health.emrys.afon.acceptance;

import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.catalogs.AwsCloudConfigForPolaris;
import health.emrys.afon.polaris.catalogs.AzureCloudConfigForPolaris;
import health.emrys.afon.polaris.catalogs.GoogleCloudConfigForPolaris;
import health.emrys.afon.polaris.config.AfonPolarisClient;
import health.emrys.afon.polaris.config.AfonPolarisClientFactory;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.polaris.catalogs.AwsUtils.bucketToUrl;
import static health.emrys.afon.polaris.catalogs.AzureUtils.bucketUrl;

public class AcceptanceTestConfig {

    public static final AcceptanceTestConfig instance;

    static {
        instance = new AcceptanceTestConfig(new FilePropertyProvider().getProperties());
    }

    private final AfonPolarisClientFactory clientFactory = AfonPolarisClientFactory.clientFactory();

    private final AfonPolarisClient           polarisAdminClient;
    private final AwsCloudConfigForPolaris    awsCloudConfigForPolaris;
    private final AwsCloudConfigForPolaris    awsForbidden;
    private final AzureCloudConfigForPolaris  azureCloudConfigForPolaris;
    private final AzureCloudConfigForPolaris  azureForbidden;
    private final GoogleCloudConfigForPolaris gcpCloudConfigForPolaris;
    private final GoogleCloudConfigForPolaris gcpForbidden;
    private final String                      adminClientId;
    private final String                      adminClientSecret;
    private final Properties                  properties;
    private final AwsCloudConfigForPolaris    awsTrino;
    private final AfonPolarisClient           polarisClient;

    private AcceptanceTestConfig(Properties properties) {
        properties.put(KC_HOSTNAME.getName(), ContainerHelper.KEYCLOAK_HOSTNAME);
        this.adminClientId     = POLARIS_ID.getValueFrom(properties);
        this.adminClientSecret = ***REMOVED***
        this.properties        = properties;
        startContainers(properties);
        this.polarisAdminClient       = polarisClientFor(adminClientId, adminClientSecret);
        this.polarisClient            = clientFactory.getPolarisClient(properties);
        this.awsCloudConfigForPolaris = new AwsCloudConfigForPolaris(properties);
        this.awsTrino                 = new AwsCloudConfigForPolaris(properties) {
            @Override
            public String getBucketUri() {
                return bucketToUrl(AWS_S3_BUCKET_NAME_TRINO.getValueFrom(properties));
            }

            @Override
            public String warehouseName() {
                return AfonCloud.AWS_TRINO.toString();
            }
        };
        this.awsForbidden               = new AwsCloudConfigForPolaris(properties) {
            @Override
            public String getBucketUri() {
                return bucketToUrl(AWS_S3_BUCKET_NAME_FORBIDDEN.getValueFrom(properties));
            }

            @Override
            public String warehouseName() {
                return AfonCloud.AWS_FORBIDDEN.toString();
            }
        };
        this.gcpForbidden               = new GoogleCloudConfigForPolaris(properties) {
            @Override
            public String warehouseName() {
                return AfonCloud.GCP_FORBIDDEN.toString();
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
                return AfonCloud.AZURE_FORBIDDEN.toString();
            }

            @Override
            public String getBucketUri() {
                return bucketUrl(
                    AZURE_BUCKET_FORBIDDEN.getValueFrom(properties),
                    AZURE_STORAGE_ACCOUNT.getValueFrom(properties));
            }
        };
    }

    public AfonPolarisClient polarisClientFor(String clientId, String clientSecret) {
        return clientFactory.getPolarisClient(
            POLARIS_ENDPOINT.getValueFrom(this.properties),
            clientId,
            clientSecret,
            ContainerHelper.KEYCLOAK_HOSTNAME);
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

    public AfonPolarisClient getPolarisAdminClient() {
        return polarisAdminClient;
    }

    public String getAdminAccessToken() {
        return polarisAdminClient.getAccessToken();
    }

    public String getAdminClientId() {
        return adminClientId;
    }

    public String getAdminClientSecret() {
        return adminClientSecret;
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

    public Properties getProperties() {
        Properties props = new Properties();
        props.putAll(properties);
        return props;
    }
}
