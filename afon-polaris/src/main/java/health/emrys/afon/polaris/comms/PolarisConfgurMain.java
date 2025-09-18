package health.emrys.afon.polaris.comms;

import health.emrys.afon.polaris.catalogs.AwsCloudConfigForPolaris;
import health.emrys.afon.polaris.catalogs.CloudConfigForPolaris;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.PROJECT_PROPERTIES_FILE;
import static java.lang.String.format;

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

        callPolaris(properties, config);
    }

    private static void callPolaris(Properties properties, CloudConfigForPolaris config) throws IOException, IncorrectConfigException {
        var    client      = new AfonPolarisClient(properties);
        String accessToken = client.getAccessToken();
        client.postJsonCall("/api/management/v1/catalogs",
                            config.catalogConfig(),
                            accessToken);
        client.putJsonCall(format("/api/management/v1/catalogs/%s/catalog-roles/catalog_admin/grants", config.warehouseName()),
                           config.catalogAdminGrants(),
                           accessToken);
        client.putJsonCall(format("/api/management/v1/principal-roles/service_admin/catalog-roles/%s", config.warehouseName()),
                           config.catalogRoles(),
                           accessToken);
    }

}
