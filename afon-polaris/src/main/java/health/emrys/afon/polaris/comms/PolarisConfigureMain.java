package health.emrys.afon.polaris.comms;

import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.catalogs.AwsCloudConfigForPolaris;
import health.emrys.afon.polaris.catalogs.CloudConfigForPolaris;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;

import java.io.IOException;
import java.util.Properties;

public class PolarisConfigureMain {

    public static void main(String[] args) {
        try {
            new PolarisConfigureMain().smokeTest();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void smokeTest() throws Exception {
        AfonPropertiesLoader  loader     = new AfonPropertiesLoader();
        Properties            properties = loader.getProperties();
        CloudConfigForPolaris config     = new AwsCloudConfigForPolaris(properties);

        callPolaris(properties, config);
    }

    public void callPolaris(Properties properties, CloudConfigForPolaris config) throws IOException, IncorrectConfigException {
        var    client      = new AfonPolarisClient(properties);
        var    configurer  = new PolarisConfigurer(client);
        String accessToken = client.getAccessToken();
        configurer.configurCatalogFor(config, accessToken);
    }


}
