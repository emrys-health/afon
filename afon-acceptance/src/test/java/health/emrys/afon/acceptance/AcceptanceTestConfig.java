package health.emrys.afon.acceptance;

import health.emrys.afon.config.*;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.polaris.catalogs.AwsPolarisCatalog;
import health.emrys.afon.polaris.catalogs.AzurePolarisCatalog;
import health.emrys.afon.polaris.catalogs.GcpPolarisCatalog;
import health.emrys.afon.polaris.catalogs.PolarisCatalog;
import health.emrys.afon.polaris.config.PolarisCatalogManager;

import java.util.HashMap;
import java.util.Map;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.config.ConfigUtils.afonCloud;
import static health.emrys.afon.polaris.catalogs.AwsUtils.s3BucketUrl;
import static health.emrys.afon.polaris.catalogs.AzureUtils.abfssBucketUrl;
import static health.emrys.afon.polaris.catalogs.GcpUtils.gsBucketUrl;

public class AcceptanceTestConfig {

    public static final AcceptanceTestConfig instance;

    static {
        instance = new AcceptanceTestConfig(new OpsPropertyProvider());
    }

    private final AfonPropertyProvider properties;
    private final AfonCatalogs afonCatalogs;
    private final PolarisCatalogManager catalogManager;
    private final Map<AfonCatalog, PolarisCatalog> polarisCatalogs;

    private AcceptanceTestConfig(AfonPropertyProvider properties) {
        this.properties = properties;
        this.afonCatalogs = new AfonCatalogs(properties);
        this.properties.setValue(OIDC_DOMAIN_NAME, ContainerHelper.KEYCLOAK_HOSTNAME);
        this.polarisCatalogs = new HashMap<>();
        addPolarisCatalogs();
        startContainers(this.properties);
        this.catalogManager = new PolarisCatalogManager(this.properties);
    }

    private void addPolarisCatalogs() {
        switch ( afonCloud() ){
            case "aws":
                addAwsCatalogs();
                break;
            case "azure":
                addAzureCatalogs();
                break;
            case "gcp":
                addGcpCatalogs();
                break;
            default:
                addAwsCatalogs();
                addAzureCatalogs();
                addGcpCatalogs();

        }
    }

    private void addAwsCatalogs() {
        polarisCatalogs.put(afonCatalogs.aws(), new AwsPolarisCatalog(this.properties));
        polarisCatalogs.put(afonCatalogs.awsForbidden(), new AwsPolarisCatalog(this.properties) {
            @Override
            public String bucketUri() {
                return s3BucketUrl(properties.getValue(AWS_S3_BUCKET_NAME_FORBIDDEN));
            }

            @Override
            public String catalogName() {
                return afonCatalogs.awsForbidden().toString();
            }
        });
        polarisCatalogs.put(afonCatalogs.awsTrino(), new AwsPolarisCatalog(this.properties) {
            @Override
            public String bucketUri() {
                return s3BucketUrl(properties.getValue(AWS_S3_BUCKET_NAME_TRINO));
            }

            @Override
            public String catalogName() {
                return afonCatalogs.awsTrino().toString();
            }
        });
    }

    private void addAzureCatalogs() {
        polarisCatalogs.put(afonCatalogs.azure(), new AzurePolarisCatalog(this.properties));
        polarisCatalogs.put(afonCatalogs.azureForbidden(), new AzurePolarisCatalog(this.properties) {
            @Override
            public String bucketUri() {
                return abfssBucketUrl(
                        properties.getValue(AZURE_BUCKET_NAME_FORBIDDEN),
                        properties.getValue(AZURE_STORAGE_ACCOUNT));
            }

            @Override
            public String catalogName() {
                return afonCatalogs.azureForbidden().toString();
            }
        });
    }

    private void addGcpCatalogs() {
        polarisCatalogs.put(afonCatalogs.gcp(), new GcpPolarisCatalog(this.properties));
        polarisCatalogs.put(afonCatalogs.gcpForbidden(), new GcpPolarisCatalog(this.properties) {
            @Override
            public String bucketUri() {
                return gsBucketUrl(properties.getValue(GCP_BUCKET_NAME_FORBIDDEN));
            }

            @Override
            public String catalogName() {
                return afonCatalogs.gcpForbidden().toString();
            }
        });
    }

    private static void startContainers(AfonPropertyProvider properties) {
        ContainerHelper containerHelper = ContainerHelper.instance;
        containerHelper.startKeyCloak(properties);
        containerHelper.startPolaris(properties);
        if (testConfigIncludes(AWS)){
            containerHelper.startTrino(properties);
        }
    }

    public static boolean testConfigIncludes(AfonCloud cloud) {
        String afonCloud = afonCloud();
        return afonCloud.isEmpty() || afonCloud.equalsIgnoreCase(cloud.toString());
    }

    public AfonPropertyProvider getProperties() {
        return this.properties;
    }

    public AfonCatalogs afonCatalogs() {
        return this.afonCatalogs;
    }

    public PolarisCatalogManager getCatalogManager() {
        return catalogManager;
    }

    public PolarisCatalog getCatalog(AfonCatalog id) {
        return polarisCatalogs.get(id);
    }

    public String redactSecrets(String content, String sourceId) {
        return Redacter.redactSecrets(content, sourceId, properties.getSecretProperties());
    }

}
