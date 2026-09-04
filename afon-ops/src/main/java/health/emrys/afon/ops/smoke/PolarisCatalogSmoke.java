package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.catalogs.AwsPolarisCatalog;
import health.emrys.afon.polaris.catalogs.PolarisCatalog;
import health.emrys.afon.polaris.config.PolarisCatalogClient;

import static health.emrys.afon.config.AfonProperty.AWS_S3_BUCKET_NAME_TRINO;
import static health.emrys.afon.polaris.catalogs.AwsUtils.s3BucketUrl;

public class PolarisCatalogSmoke {

    public void smokeTest(PolarisCatalogClient client, AfonPropertyProvider properties) {
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
            client.configureCatalogs(catalog);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        var app = new PolarisCatalogSmoke();
        var properties = new FilePropertyProvider();
        PolarisCatalogClient client = new PolarisCatalogClient(properties);
        app.smokeTest(client, properties);
    }
}
