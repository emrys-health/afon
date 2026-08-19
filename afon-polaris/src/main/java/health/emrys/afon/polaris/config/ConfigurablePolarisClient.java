package health.emrys.afon.polaris.config;

import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.polaris.catalogs.*;
import health.emrys.afon.security.SecurityUtils;
import org.apache.hc.core5.http.HttpResponse;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperty.POLARIS_ID;
import static health.emrys.afon.config.AfonProperty.POLARIS_SECRET;

public class ConfigurablePolarisClient {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(ConfigurablePolarisClient.class);
    private final Properties properties;

    public ConfigurablePolarisClient(Properties properties) {
        this.properties = properties;
    }

    public List<HttpResponse> configureAllCatalogs(String credentials) throws IncorrectConfigException, IOException {
        PolarisCatalog[] catalogs = new PolarisCatalog[]{
                new AwsPolarisCatalog(properties),
                new GcpPolarisCatalog(properties),
                new AzurePolarisCatalog(properties),
                new GlueAwsPolarisCatalog(properties),
                new AzureExternalPolarisCatalog(properties),
        };
        return configureCatalogs(credentials, catalogs);
    }

    public List<HttpResponse> configureCatalogs(String credentials, PolarisCatalog[] catalogs) throws IOException, IncorrectConfigException {
        List<HttpResponse> allResponses = new ArrayList<HttpResponse>();
        for (PolarisCatalog catalog : catalogs) {
            logger.info("Configuring {}", catalog.catalogName());
            allResponses.addAll(Arrays.asList(configureCatalog(credentials, catalog)));
        }
        return allResponses;
    }

    private HttpResponse[] configureCatalog(String credentials, PolarisCatalog catalog) throws IOException, IncorrectConfigException {
        String endpoint = AfonProperty.POLARIS_ENDPOINT.getValueFrom(properties);
        String protocol = SecurityUtils.protocolFor(endpoint);
        var client = PolarisRestClientFactory.clientFactory().createClient(addPolarisCredentialsTo(credentials, properties), protocol);
        var configurer = new PolarisConfigurer(client);
        return configurer.configureCatalog(catalog);
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

}