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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.config.ConfigUtils.afonCloud;
import static health.emrys.afon.polaris.catalogs.AwsUtils.s3BucketUrl;
import static health.emrys.afon.polaris.catalogs.AzureUtils.abfssBucketUrl;
import static health.emrys.afon.polaris.catalogs.GcpUtils.gsBucketUrl;

public class AcceptanceTestConfig {

    public static final AcceptanceTestConfig instance;

    static {
        instance = new AcceptanceTestConfig(new FilePropertyProvider());
    }

    private final AfonPropertyProvider properties;
    private final PolarisCatalogManager catalogManager;
    private final Map<AfonCloud, PolarisCatalog> polarisCatalogs;

    private AcceptanceTestConfig(AfonPropertyProvider properties) {
        this.properties = properties;
        this.properties.setValue(KC_HOSTNAME, ContainerHelper.KEYCLOAK_HOSTNAME);
        this.polarisCatalogs = new HashMap<>();
        addPolarisCatalogs();
        startContainers(this.properties);
        this.catalogManager = new PolarisCatalogManager(this.properties);
    }

    private void addPolarisCatalogs() {
        List<PolarisCatalog> catalogs = new ArrayList<>();
        switch ( afonCloud() ){
            case "aws":
                polarisCatalogs.put(AfonCloud.AWS, new AwsPolarisCatalog(this.properties));
                polarisCatalogs.put(AfonCloud.AWS_FORBIDDEN, new AwsPolarisCatalog(this.properties) {
                    @Override
                    public String bucketUri() {
                        return s3BucketUrl(properties.getValue(AWS_S3_BUCKET_NAME_FORBIDDEN));
                    }

                    @Override
                    public String catalogName() {
                        return AfonCloud.AWS_FORBIDDEN.toString();
                    }
                });
                polarisCatalogs.put(AfonCloud.AWS_TRINO, new AwsPolarisCatalog(this.properties) {
                    @Override
                    public String bucketUri() {
                        return s3BucketUrl(properties.getValue(AWS_S3_BUCKET_NAME_TRINO));
                    }

                    @Override
                    public String catalogName() {
                        return AfonCloud.AWS_TRINO.toString();
                    }
                });
                break;
            case "azure":
                polarisCatalogs.put(AfonCloud.AZURE, new AzurePolarisCatalog(this.properties));
                polarisCatalogs.put(AfonCloud.AZURE_FORBIDDEN, new AzurePolarisCatalog(this.properties) {
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
                });
                break;
            case "gcp":
                polarisCatalogs.put(AfonCloud.GCP, new GcpPolarisCatalog(this.properties));
                polarisCatalogs.put(AfonCloud.GCP_FORBIDDEN, new GcpPolarisCatalog(this.properties) {
                    @Override
                    public String bucketUri() {
                        return gsBucketUrl(properties.getValue(GCP_BUCKET_NAME_FORBIDDEN));
                    }

                    @Override
                    public String catalogName() {
                        return AfonCloud.GCP_FORBIDDEN.toString();
                    }
                });
                break;
            default:
                polarisCatalogs.put(AfonCloud.AWS, new AwsPolarisCatalog(this.properties));
                polarisCatalogs.put(AfonCloud.AWS_FORBIDDEN, new AwsPolarisCatalog(this.properties) {
                    @Override
                    public String bucketUri() {
                        return s3BucketUrl(properties.getValue(AWS_S3_BUCKET_NAME_FORBIDDEN));
                    }

                    @Override
                    public String catalogName() {
                        return AfonCloud.AWS_FORBIDDEN.toString();
                    }
                });
                polarisCatalogs.put(AfonCloud.AWS_TRINO, new AwsPolarisCatalog(this.properties) {
                    @Override
                    public String bucketUri() {
                        return s3BucketUrl(properties.getValue(AWS_S3_BUCKET_NAME_TRINO));
                    }

                    @Override
                    public String catalogName() {
                        return AfonCloud.AWS_TRINO.toString();
                    }
                });
                polarisCatalogs.put(AfonCloud.AZURE, new AzurePolarisCatalog(this.properties));
                polarisCatalogs.put(AfonCloud.AZURE_FORBIDDEN, new AzurePolarisCatalog(this.properties) {
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
                });
                polarisCatalogs.put(AfonCloud.GCP, new GcpPolarisCatalog(this.properties));
                polarisCatalogs.put(AfonCloud.GCP_FORBIDDEN, new GcpPolarisCatalog(this.properties) {
                    @Override
                    public String bucketUri() {
                        return gsBucketUrl(properties.getValue(GCP_BUCKET_NAME_FORBIDDEN));
                    }

                    @Override
                    public String catalogName() {
                        return AfonCloud.GCP_FORBIDDEN.toString();
                    }
                });

        }
    }

    private static void startContainers(AfonPropertyProvider properties) {
        ContainerHelper containerHelper = ContainerHelper.instance;
        containerHelper.startKeyCloak(properties);
        containerHelper.startPolaris(properties);
        if (isAfonCloudEmptyOrEqualTo("aws")){
            containerHelper.startTrino(properties);
        }
    }

    public static boolean isAfonCloudEmptyOrEqualTo(String cloud) {
        String afonCloud = afonCloud();
        return afonCloud.isEmpty() || afonCloud.equalsIgnoreCase(cloud);
    }

    public AfonPropertyProvider getProperties() {
        return this.properties;
    }

    public PolarisCatalogManager getCatalogManager() {
        return catalogManager;
    }

    public PolarisCatalog getAwsCatalog() {
        return polarisCatalogs.get(AfonCloud.AWS);
    }

    public PolarisCatalog getAwsForbidden() {
        return polarisCatalogs.get(AfonCloud.AWS_FORBIDDEN);
    }

    public PolarisCatalog getAwsTrino() {
        return polarisCatalogs.get(AfonCloud.AWS_TRINO);
    }

    public PolarisCatalog getAzureCatalog() {
        return polarisCatalogs.get(AfonCloud.AZURE);
    }

    public PolarisCatalog getAzureForbidden() {
        return polarisCatalogs.get(AfonCloud.AZURE_FORBIDDEN);
    }

    public PolarisCatalog getGcpCatalog() {
        return polarisCatalogs.get(AfonCloud.GCP);
    }

    public PolarisCatalog getGcpForbidden() {
        return polarisCatalogs.get(AfonCloud.GCP_FORBIDDEN);
    }

    public String redactSecrets(String content, String sourceId) {
        return Redacter.redactSecrets(content, sourceId, properties.getSecretProperties());
    }

}
