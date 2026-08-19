package health.emrys.afon.polaris.config;

import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.polaris.catalogs.*;
import health.emrys.afon.security.SecurityUtils;
import org.apache.hc.core5.http.HttpResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.config.AfonProperty.AWS_REGION;
import static health.emrys.afon.config.AfonProperty.AWS_S3_BUCKET_NAME;
import static health.emrys.afon.polaris.catalogs.AwsUtils.bucketToUrl;

class AwsPolarisCatalogNonPermissioned extends AwsPolarisCatalog {
    public AwsPolarisCatalogNonPermissioned(Properties properties) {
        super(
            AWS_S3_ROLE_ARN.getValueFrom(properties),
            bucketToUrl(AWS_S3_BUCKET_NAME_FORBIDDEN_BY_POLARIS.getValueFrom(properties)),
            AWS_REGION.getValueFrom(properties));
    }
}

public class PolarisCatalogClient {

    static final  Logger     logger = org.slf4j.LoggerFactory.getLogger(PolarisCatalogClient.class);
    private final Properties properties;

    public PolarisCatalogClient(Properties properties) {
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
        PolarisConfigurer  configurer   = polarisConfigurer(credentials);
        for (PolarisCatalog catalog : catalogs) {
            logger.info("Configuring {}", catalog.catalogName());
            allResponses.addAll(Arrays.asList(configurer.configureCatalog(catalog)));
        }

        allResponses.add(configurer.createCatalog(new AwsPolarisCatalogNonPermissioned(properties)));

        return allResponses;
    }

    private @NonNull PolarisConfigurer polarisConfigurer(String credentials) {
        String endpoint = AfonProperty.POLARIS_ENDPOINT.getValueFrom(properties);
        String protocol = SecurityUtils.protocolFor(endpoint);
        var    client   = PolarisRestClientFactory.clientFactory().createClient(addPolarisCredentialsTo(credentials, properties), protocol);
        return new PolarisConfigurer(client);
    }

    /**
     * Adds id:secret credentials to properties using keys {@link AfonProperty#POLARIS_ID} and {@link AfonProperty#POLARIS_SECRET}.
     *
     * @param credentials the id:secret String
     * @param properties  the Properties to add to
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