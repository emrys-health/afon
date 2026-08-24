package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.catalogs.AwsPolarisCatalog;
import health.emrys.afon.polaris.catalogs.PolarisCatalog;
import health.emrys.afon.polaris.config.PolarisConfigurer;
import health.emrys.afon.polaris.config.PolarisRestClient;
import health.emrys.afon.polaris.config.PolarisRestClientFactory;

import static health.emrys.afon.config.AfonProperty.AWS_S3_BUCKET_NAME_TRINO;
import static health.emrys.afon.polaris.catalogs.AwsUtils.bucketToUrl;

public class PolarisConfigurerSmoke {

    public void smokeTest(PolarisConfigurer configurer, AfonPropertyProvider properties) {
        PolarisCatalog config = new AwsPolarisCatalog(properties) {
            @Override
            public String bucketUri() {
                return bucketToUrl(properties.getValue(AWS_S3_BUCKET_NAME_TRINO));
            }

            @Override
            public String catalogName() {
                return AfonCloud.AWS_TRINO.toString();
            }
        };
        try {
            configurer.configureCatalog(config);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        var app = new PolarisConfigurerSmoke();
        var properties = new FilePropertyProvider();
        PolarisRestClient client = PolarisRestClientFactory.clientFactory().createClient(properties, "http");
        PolarisConfigurer configurer = new PolarisConfigurer(client);
        app.smokeTest(configurer, properties);
    }
}
