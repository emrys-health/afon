package health.emrys.afon.polaris.config;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.polaris.catalogs.*;
import health.emrys.afon.security.SecurityUtils;
import org.apache.hc.core5.http.HttpResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static health.emrys.afon.config.AfonProperty.*;

public class PolarisCatalogClient {

    static final  Logger     logger = org.slf4j.LoggerFactory.getLogger(PolarisCatalogClient.class);
    private final AfonPropertyProvider properties;
    private final PolarisCatalog[] catalogs;

    private static final Map<AfonCloud, Function<AfonPropertyProvider, PolarisCatalog>>
            CLOUD_TO_CATALOG = Map.of(
            AfonCloud.AWS, AwsPolarisCatalog::new,
            AfonCloud.AZURE, AzurePolarisCatalog::new,
            AfonCloud.GCP, GcpPolarisCatalog::new,
            AfonCloud.AZURE_EXTERNAL, AzureExternalPolarisCatalog::new,
            AfonCloud.GCP_EXTERNAL, GcpExternalPolarisCatalog::new);

    public PolarisCatalogClient(AfonPropertyProvider properties) {
        this.properties = properties;
        // HDRS (Patch 4): AWS-only deployment. Upstream also configures Google,
        // Azure, Glue and external-Azure catalogs here, and each constructor
        // throws on that cloud's missing properties - so an AWS-only config
        // cannot even start the loop without this cut-down.
        //
        // AwsForbiddenPolarisCatalog is AWS but is also left out: it is new
        // upstream work needing AWS_S3_BUCKET_NAME_FORBIDDEN_BY_POLARIS, a
        // bucket infra-aws-poc does not create. Add the bucket and this entry
        // together if the forbidden-catalog behaviour is ever wanted here.
        this.catalogs = new PolarisCatalog[]{
                new AwsPolarisCatalog(properties),
        };
    }

    public PolarisCatalog getCatalog(String name) {
        return CLOUD_TO_CATALOG.get(AfonCloud.valueOf(name)).apply(properties);
    }

    public List<String> getCatalogNames() {
        return Arrays.stream(catalogs).map(PolarisCatalog::catalogName).toList();
    }

    public List<HttpResponse> configureAllCatalogs(String credentials) throws IncorrectConfigException, IOException {
        return configureCatalogs(credentials, catalogs);
    }

    public List<HttpResponse> configureCatalogs(String credentials, PolarisCatalog[] catalogs) throws IOException, IncorrectConfigException {
        List<HttpResponse> allResponses = new ArrayList<HttpResponse>();
        PolarisConfigurer  configurer   = polarisConfigurer(credentials);
        for (PolarisCatalog catalog : catalogs) {
            logger.info("Configuring {}", catalog.catalogName());
            allResponses.addAll(configurer.configureCatalog(catalog));
        }
        return allResponses;
    }

    private @NonNull PolarisConfigurer polarisConfigurer(String credentials) {
        String endpoint = properties.getValue(POLARIS_ENDPOINT);
        String protocol = SecurityUtils.protocolFor(endpoint);
        addPolarisCredentialsTo(credentials, properties);
        var    client   = PolarisRestClientFactory.clientFactory().createClient(properties, protocol);
        return new PolarisConfigurer(client);
    }

    /**
     * Sets id:secret credentials to {@link AfonPropertyProvider} using keys {@link AfonProperty#POLARIS_ID} and {@link AfonProperty#POLARIS_SECRET}.
     *
     * @param credentials the id:secret String
     * @param properties  the Properties to add to
     */
    public static void addPolarisCredentialsTo(String credentials, AfonPropertyProvider properties) {
        String[] creds = credentials.split(":");
        if (creds.length != 2) {
            logger.error("Incorrect access token {}. The token should have a ':' delimiting ID from Secret", credentials);
        }
        properties.setValue(POLARIS_ID, creds[0]);
        properties.setValue(POLARIS_SECRET, creds[1]);
    }

}