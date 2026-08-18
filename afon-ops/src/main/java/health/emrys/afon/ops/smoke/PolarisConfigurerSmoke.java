package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.catalogs.AwsCloudConfigForPolaris;
import health.emrys.afon.polaris.catalogs.CloudConfigForPolaris;
import health.emrys.afon.polaris.config.AfonPolarisClient;
import health.emrys.afon.polaris.config.AfonPolarisClientFactory;
import health.emrys.afon.polaris.config.PolarisConfigurer;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperty.AWS_S3_BUCKET_NAME_TRINO;
import static health.emrys.afon.polaris.catalogs.AwsUtils.bucketToUrl;

public class PolarisConfigurerSmoke {

    public void smokeTest() {
        Properties properties = new FilePropertyProvider().getProperties();
        AfonPolarisClient client = AfonPolarisClientFactory.clientFactory().getPolarisClient(properties, "http");
        PolarisConfigurer configurer = new PolarisConfigurer(client);
        CloudConfigForPolaris config     = new AwsCloudConfigForPolaris(properties) {
            @Override
            public String getBucketUri() {
                return bucketToUrl(AWS_S3_BUCKET_NAME_TRINO.getValueFrom(properties));
            }

            @Override
            public String warehouseName() {
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
        app.smokeTest();
    }
}
