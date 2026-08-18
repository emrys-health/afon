package health.emrys.afon.polaris.config;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.catalogs.*;
import org.apache.hc.core5.http.HttpResponse;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperty.POLARIS_ID;
import static health.emrys.afon.config.AfonProperty.POLARIS_SECRET;

public class PolarisConfigClient {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(PolarisConfigClient.class);

    public PolarisConfigClient() {
    }

    /**
     * Adds id:secret credentials to properties using keys {@link AfonProperty#POLARIS_ID} and {@link AfonProperty#POLARIS_SECRET}.
     * @param credentials the id:secret String
     * @param properties the Properties to add to
     * @return The updated properties
     */
    public static Properties addPolarisCredentialsTo(String credentials, Properties properties) {
        String[] creds = credentials.split(":");
        if (creds.length != 2) {
            logger.error("Incorrect access token {}. The token should have a ':' delimiting ID from Secret", credentials);
        }
        properties.setProperty(POLARIS_ID.getName(), creds[0]);
        properties.setProperty(POLARIS_SECRET.getName(), creds[1]);
        return properties;
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
        List<HttpResponse> allResponses = new ArrayList<HttpResponse>();
        properties = addPolarisCredentialsTo(credentials, properties);
        for (CloudConfigForPolaris config : configs) {
            logger.info("Configuring {}", config.warehouseName());
            HttpResponse[] responses = callPolaris(properties, config);
            Arrays.stream(responses).forEach(PolarisConfigClient::logIfProblem);
            allResponses.addAll(Arrays.asList(responses));
        }
        return allResponses;
    }

    static void logIfProblem(HttpResponse response) {
        if (response.getCode() / 100 != 2) {
            logger.error("HTTP code {}. Error = {}", response.getCode(), response.getReasonPhrase());
        }
    }

    public void smokeTest() throws Exception {
        Properties properties = new FilePropertyProvider().getProperties();
        CloudConfigForPolaris config = new AwsCloudConfigForPolaris(properties) {
            @Override
            public String getBucketUri() {
                return AwsUtils.bucketToUrl(AfonProperty.AWS_S3_BUCKET_NAME_TRINO.getValueFrom(properties));
            }

            @Override
            public String warehouseName() {
                return AfonCloud.AWS_TRINO.toString();
            }
        };
        logger.info("Configuring {}", config.warehouseName());
        callPolaris(properties, config);
    }

    public HttpResponse[] callPolaris(Properties properties, CloudConfigForPolaris config) throws IOException, IncorrectConfigException {
        String protocol = "https";
        if (AfonProperty.POLARIS_ENDPOINT.getValueFrom(properties).toLowerCase().contains("localhost")) {
            protocol = "http";
        }
        var client = AfonPolarisClientFactory.clientFactory().getPolarisClient(properties, protocol);
        var configurer = new PolarisConfigurer(client);
        String accessToken = client.getAccessToken();
        logger.info("Access token {}", accessToken);
        return configurer.configureCatalog(config, accessToken);
    }
}