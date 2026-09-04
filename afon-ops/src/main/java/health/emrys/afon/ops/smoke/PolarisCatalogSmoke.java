package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.catalogs.AwsPolarisCatalog;
import health.emrys.afon.polaris.catalogs.PolarisCatalog;
import health.emrys.afon.polaris.config.CatalogConfigurer;
import health.emrys.afon.polaris.config.PolarisRestClient;
import health.emrys.afon.polaris.config.PolarisRestClientFactory;

import static health.emrys.afon.config.AfonProperty.AWS_S3_BUCKET_NAME_TRINO;
import static health.emrys.afon.polaris.catalogs.AwsUtils.s3BucketUrl;

public class PolarisCatalogSmoke {

    public void smokeTest(CatalogConfigurer configurer, AfonPropertyProvider properties) {
        PolarisCatalog catalog = new AwsPolarisCatalog(properties) {
            @Override
            public String bucketUri() {
                return s3BucketUrl(properties.getValue(AWS_S3_BUCKET_NAME_TRINO));
            }

            @Override
            public String catalogName() {
                return AfonCloud.AWS_TRINO.toString();
            }
        };
        try {
            configurer.configureCatalog(catalog);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        var app = new PolarisCatalogSmoke();
        var properties = new FilePropertyProvider();
        PolarisRestClient client = PolarisRestClientFactory.clientFactory().createClient(properties, "http");
        CatalogConfigurer configurer = new CatalogConfigurer(client);
        app.smokeTest(configurer, properties);
    }
}
