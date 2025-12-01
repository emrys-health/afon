package health.emrys.afon.polaris.comms;

import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.catalogs.*;
import org.apache.hc.core5.http.HttpResponse;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Arrays;
import java.util.Properties;

import static health.emrys.afon.config.AfonPropertiesLoader.add;

public class PolarisConfigureMain {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(PolarisConfigureMain.class);

    public static void main(String[] args) {
        try {
            PolarisConfigureMain app = new PolarisConfigureMain();
            if (args.length == 0) {
                logger.info("Running smoke test");
                app.smokeTest();
            } else {
                AfonPropertiesLoader loader = null;
                if (args.length == 1) {
                    loader = new AfonPropertiesLoader();
                } else {
                    loader = new AfonPropertiesLoader(args[1]);
                }
                Properties props = loader.getProperties();
                app.createAllCatalogs(props, args[0]);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void createAllCatalogs(Properties properties, String credentials) throws IncorrectConfigException, IOException {
        properties = add(credentials, properties);
        CloudConfigForPolaris[] configs = new CloudConfigForPolaris[]{
                new AwsCloudConfigForPolaris(properties),
                new GoogleCloudConfigForPolaris(properties),
                new AzureCloudConfigForPolaris(properties),
                };
        for (CloudConfigForPolaris config : configs) {
            logger.info("Configuring {}", config.warehouseName());
            var responses = callPolaris(properties, config);
            Arrays.stream(responses).forEach(PolarisConfigureMain::logIfProblem);
        }
    }

    private static void logIfProblem(HttpResponse response) {
        if (response.getCode() / 100 != 2) {
            logger.error("HTTP code {}. Error = {}", response.getCode(), response.getReasonPhrase());
        }
    }

    private void smokeTest() throws Exception {
        AfonPropertiesLoader  loader     = new AfonPropertiesLoader();
        Properties            properties = loader.getProperties();
        CloudConfigForPolaris config     = new AwsCloudConfigForPolaris(properties);
        logger.info("Configuring {}", config.warehouseName());
        callPolaris(properties, config);
    }

    public HttpResponse[] callPolaris(Properties properties, CloudConfigForPolaris config) throws IOException, IncorrectConfigException {
        var    client      = new AfonPolarisClient(properties, "https");
        var    configurer  = new PolarisConfigurer(client);
        String accessToken = client.getAccessToken();
        return configurer.configurCatalogFor(config, accessToken);
    }


}
