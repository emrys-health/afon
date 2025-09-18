package health.emrys.afon.polaris.comms;

import health.emrys.afon.polaris.catalogs.AwsCloudConfigForPolaris;
import health.emrys.afon.polaris.catalogs.CloudConfigForPolaris;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.PROJECT_PROPERTIES_FILE;

public class PolarisConfgurMain {

    public static void main(String[] args) {
        try {
            new PolarisConfgurMain().smokeTest();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void smokeTest() throws Exception {
        Properties properties = new Properties();
        properties.load(new FileInputStream(PROJECT_PROPERTIES_FILE));
        CloudConfigForPolaris config = new AwsCloudConfigForPolaris(properties);
        System.out.println(config.catalogConfig());

        var client = new AfonPolarisClient(properties);
        System.out.println(client.getAccessToken());
    }

}
