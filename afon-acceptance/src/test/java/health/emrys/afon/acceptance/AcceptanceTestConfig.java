package health.emrys.afon.acceptance;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.Redacter;
import health.emrys.afon.polaris.catalogs.AwsPolarisCatalog;
import health.emrys.afon.polaris.catalogs.AzurePolarisCatalog;
import health.emrys.afon.polaris.catalogs.GcpPolarisCatalog;
import health.emrys.afon.polaris.config.PolarisRestClient;
import health.emrys.afon.polaris.config.PolarisRestClientFactory;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.polaris.catalogs.AwsUtils.bucketToUrl;
import static health.emrys.afon.polaris.catalogs.AzureUtils.bucketUrl;

public class AcceptanceTestConfig {

    public static final AcceptanceTestConfig instance;

    static {
        instance = new AcceptanceTestConfig(new FilePropertyProvider());
    }

    private final PolarisRestClientFactory clientFactory = PolarisRestClientFactory.clientFactory();

    private final PolarisRestClient polarisAdminClient;
    private final AwsPolarisCatalog awsPolarisCatalog;
    private final AwsPolarisCatalog awsForbidden;
    private final AzurePolarisCatalog azurePolarisCatalog;
    private final AzurePolarisCatalog azureForbidden;
    private final GcpPolarisCatalog gcpCloudConfigForPolaris;
    private final GcpPolarisCatalog gcpForbidden;
    private final String                      adminClientId;
    private final String                      adminClientSecret;
    private final AwsPolarisCatalog awsTrino;
    private final PolarisRestClient polarisClient;
    private final AfonPropertyProvider properties;

    private AcceptanceTestConfig(AfonPropertyProvider properties) {
        this.properties = properties;
        this.properties.setValue(KC_HOSTNAME, ContainerHelper.KEYCLOAK_HOSTNAME);
        this.adminClientId     = properties.getValue(POLARIS_ID);
        this.adminClientSecret = ***REMOVED***
        startContainers(this.properties);
        this.polarisAdminClient       = polarisClientFor(adminClientId, adminClientSecret);
        this.polarisClient            = clientFactory.createClient(this.properties);
        this.awsPolarisCatalog = new AwsPolarisCatalog(this.properties);
        this.awsTrino                 = new AwsPolarisCatalog(this.properties) {
            @Override
            public String bucketUri() {
                return bucketToUrl(properties.getValue(AWS_S3_BUCKET_NAME_TRINO));
            }

            @Override
            public String catalogName() {
                return AfonCloud.AWS_TRINO.toString();
            }
        };
        this.awsForbidden               = new AwsPolarisCatalog(this.properties) {
            @Override
            public String bucketUri() {
                return bucketToUrl(properties.getValue(AWS_S3_BUCKET_NAME_FORBIDDEN));
            }

            @Override
            public String catalogName() {
                return AfonCloud.AWS_FORBIDDEN.toString();
            }
        };
        this.gcpForbidden               = new GcpPolarisCatalog(this.properties) {
            @Override
            public String catalogName() {
                return AfonCloud.GCP_FORBIDDEN.toString();
            }

            @Override
            public String bucketUri() {
                return toBucketUri(properties.getValue(GCP_BUCKET_NAME_FORBIDDEN));
            }
        };
        this.gcpCloudConfigForPolaris   = new GcpPolarisCatalog(this.properties);
        this.azurePolarisCatalog = new AzurePolarisCatalog(this.properties);
        this.azureForbidden             = new AzurePolarisCatalog(this.properties) {
            public String catalogName() {
                return AfonCloud.AZURE_FORBIDDEN.toString();
            }

            @Override
            public String bucketUri() {
                return bucketUrl(
                        properties.getValue(AZURE_BUCKET_FORBIDDEN),
                        properties.getValue(AZURE_STORAGE_ACCOUNT));
            }
        };
    }

    public PolarisRestClient polarisClientFor(String clientId, String clientSecret) {
        return clientFactory.createClient(
            this.properties.getValue(POLARIS_ENDPOINT),
            clientId,
            clientSecret,
            ContainerHelper.KEYCLOAK_HOSTNAME);
    }

    private static void startContainers(AfonPropertyProvider provider) {
        ContainerHelper containerHelper = ContainerHelper.instance;
        containerHelper.startKeyCloak(provider);
        containerHelper.startPolaris(provider);
        containerHelper.startTrino(provider);
    }

    public PolarisRestClient getPolarisClient() {
        return polarisClient;
    }

    public String getAccessToken() {
        return polarisClient.getAccessToken();
    }

    public PolarisRestClient getPolarisAdminClient() {
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

    public AwsPolarisCatalog getAwsCloudConfigForPolaris() {
        return awsPolarisCatalog;
    }

    public GcpPolarisCatalog getGcpCloudConfigForPolaris() {
        return gcpCloudConfigForPolaris;
    }

    public AzurePolarisCatalog getAzureCloudConfigForPolaris() {
        return azurePolarisCatalog;
    }

    public AwsPolarisCatalog getAwsForbidden() {
        return awsForbidden;
    }

    public GcpPolarisCatalog getGcpForbidden() {
        return gcpForbidden;
    }

    public AzurePolarisCatalog getAzureForbidden() {
        return azureForbidden;
    }


    public AwsPolarisCatalog getAwsTrino() {
        return awsTrino;
    }

    public AfonPropertyProvider getProperties() {
        return this.properties;
    }

    public String redactSecrets(String content, String source) {
        return Redacter.redactSecrets(content, source, properties.getSecretProperties());
    }
}
