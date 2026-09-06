package health.emrys.afon.acceptance;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.Redacter;
import health.emrys.afon.polaris.catalogs.AwsPolarisCatalog;
import health.emrys.afon.polaris.catalogs.AzurePolarisCatalog;
import health.emrys.afon.polaris.catalogs.GcpPolarisCatalog;
import health.emrys.afon.polaris.catalogs.PolarisCatalog;
import health.emrys.afon.polaris.config.PolarisCatalogManager;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.polaris.catalogs.AwsUtils.s3BucketUrl;
import static health.emrys.afon.polaris.catalogs.AzureUtils.abfssBucketUrl;
import static health.emrys.afon.polaris.catalogs.GcpUtils.gsBucketUrl;

public class AcceptanceTestConfig {

    public static final AcceptanceTestConfig instance;

    static {
        System.clearProperty("afon.cloud");
        instance = new AcceptanceTestConfig(new FilePropertyProvider());
    }

    private final AfonPropertyProvider properties;
    private final PolarisCatalogManager catalogManager;
    private final PolarisCatalog awsCatalog;
    private final PolarisCatalog awsForbidden;
    private final PolarisCatalog awsTrino;
    private final PolarisCatalog azureCatalog;
    private final PolarisCatalog azureForbidden;
    private final PolarisCatalog gcpCatalog;
    private final PolarisCatalog gcpForbidden;

    private AcceptanceTestConfig(AfonPropertyProvider properties) {
        this.properties = properties;
        this.properties.setValue(KC_HOSTNAME, ContainerHelper.KEYCLOAK_HOSTNAME);
        this.awsCatalog = new AwsPolarisCatalog(this.properties);
        this.awsForbidden = new AwsPolarisCatalog(this.properties) {
            @Override
            public String bucketUri() {
                return s3BucketUrl(properties.getValue(AWS_S3_BUCKET_NAME_FORBIDDEN));
            }

            @Override
            public String catalogName() {
                return AfonCloud.AWS_FORBIDDEN.toString();
            }
        };
        this.awsTrino = new AwsPolarisCatalog(this.properties) {
            @Override
            public String bucketUri() {
                return s3BucketUrl(properties.getValue(AWS_S3_BUCKET_NAME_TRINO));
            }

            @Override
            public String catalogName() {
                return AfonCloud.AWS_TRINO.toString();
            }
        };
        this.gcpCatalog = new GcpPolarisCatalog(this.properties);
        this.gcpForbidden = new GcpPolarisCatalog(this.properties) {
            @Override
            public String bucketUri() {
                return gsBucketUrl(properties.getValue(GCP_BUCKET_NAME_FORBIDDEN));
            }

            @Override
            public String catalogName() {
                return AfonCloud.GCP_FORBIDDEN.toString();
            }
        };
        this.azureCatalog = new AzurePolarisCatalog(this.properties);
        this.azureForbidden = new AzurePolarisCatalog(this.properties) {
            @Override
            public String bucketUri() {
                return abfssBucketUrl(
                        properties.getValue(AZURE_BUCKET_FORBIDDEN),
                        properties.getValue(AZURE_STORAGE_ACCOUNT));
            }

            @Override
            public String catalogName() {
                return AfonCloud.AZURE_FORBIDDEN.toString();
            }

        };
        startContainers(this.properties);
        this.catalogManager = new PolarisCatalogManager(this.properties);
    }

    private static void startContainers(AfonPropertyProvider properties) {
        ContainerHelper containerHelper = ContainerHelper.instance;
        containerHelper.startKeyCloak(properties);
        containerHelper.startPolaris(properties);
        containerHelper.startTrino(properties);
    }

    public AfonPropertyProvider getProperties() {
        return this.properties;
    }

    public PolarisCatalogManager getCatalogManager() {
        return catalogManager;
    }

    public PolarisCatalog getAwsCatalog() {
        return awsCatalog;
    }

    public PolarisCatalog getAwsForbidden() {
        return awsForbidden;
    }

    public PolarisCatalog getAwsTrino() {
        return awsTrino;
    }

    public PolarisCatalog getAzureCatalog() {
        return azureCatalog;
    }

    public PolarisCatalog getAzureForbidden() {
        return azureForbidden;
    }

    public PolarisCatalog getGcpCatalog() {
        return gcpCatalog;
    }

    public PolarisCatalog getGcpForbidden() {
        return gcpForbidden;
    }

    public String redactSecrets(String content, String source) {
        return Redacter.redactSecrets(content, source, properties.getSecretProperties());
    }

}
