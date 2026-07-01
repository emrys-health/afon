package health.emrys.afon.polaris.comms;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.catalogs.*;
import org.apache.hc.core5.http.HttpResponse;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.AWS_S3_BUCKET_NAME_TRINO;
import static health.emrys.afon.config.AfonProperties.POLARIS_ENDPOINT;
import static health.emrys.afon.config.AfonPropertiesLoader.add;
import static health.emrys.afon.polaris.catalogs.AwsUtils.bucketToUrl;

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

    public List<HttpResponse> createAllCatalogs(Properties properties, String credentials) throws IncorrectConfigException, IOException {
        CloudConfigForPolaris[] configs = new CloudConfigForPolaris[]{
                new AwsCloudConfigForPolaris(properties),
                new GoogleCloudConfigForPolaris(properties),
                new AzureCloudConfigForPolaris(properties),
                new GlueCloudConfigForPolaris(properties),
                new ExternalAzureCloudConfigForPolaris(properties),
                };
        return createCatalogs(properties, credentials, configs);
    }

    public List<HttpResponse> createCatalogs(Properties properties, String credentials, CloudConfigForPolaris[] configs) throws IOException, IncorrectConfigException {
        List<HttpResponse> allResponses = new ArrayList<>();
        properties = add(credentials, properties);
        for (CloudConfigForPolaris config : configs) {
            logger.info("Configuring {}", config.warehouseName());
            HttpResponse[] responses = callPolaris(properties, config);
            Arrays.stream(responses).forEach(PolarisConfigureMain::logIfProblem);
            allResponses.addAll(Arrays.asList(responses));
        }
        return allResponses;
    }

    private static void logIfProblem(HttpResponse response) {
        if (response.getCode() / 100 != 2) {
            logger.error("HTTP code {}. Error = {}", response.getCode(), response.getReasonPhrase());
        }
    }

    private void smokeTest() throws Exception {
        AfonPropertiesLoader  loader     = new AfonPropertiesLoader();
        Properties            properties = loader.getProperties();
        CloudConfigForPolaris config     = new AwsCloudConfigForPolaris(properties) {
            @Override
            public String getBucketUri() {
                return bucketToUrl(AWS_S3_BUCKET_NAME_TRINO.getValueFrom(properties));
            }

            @Override
            public String warehouseName() {
                return AfonProperties.SupportedCloudsEnum.AWS_TRINO.toString();
            }
        };
        logger.info("Configuring {}", config.warehouseName());
        callPolaris(properties, config);
    }

    public HttpResponse[] callPolaris(Properties properties, CloudConfigForPolaris config) throws IOException, IncorrectConfigException {
        String protocol    = "https";
        if (POLARIS_ENDPOINT.getValueFrom(properties).toLowerCase().contains("localhost")) {
            protocol = "http";
        }
        var    client      = new AfonPolarisClient(properties, protocol);
        var    configurer  = new PolarisConfigurer(client);
        String accessToken = client.getAccessToken();
        logger.info("Access token {}", accessToken);
        return configurer.configurCatalogFor(config, accessToken);
    }


}
