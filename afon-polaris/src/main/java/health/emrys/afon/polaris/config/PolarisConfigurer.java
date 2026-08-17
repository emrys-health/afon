package health.emrys.afon.polaris.config;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.catalogs.*;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.polaris.core.admin.model.PrincipalWithCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperty.AWS_S3_BUCKET_NAME_TRINO;
import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;
import static health.emrys.afon.polaris.catalogs.AwsUtils.bucketToUrl;
import static health.emrys.afon.polaris.config.PolarisConfigClient.addPolarisCredentialsTo;
import static java.lang.String.format;

public class PolarisConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(PolarisConfigurer.class);

    private final AfonPolarisClient client;

    public static String listTables(String warehouseName, String namespace) {
        return tableURI(warehouseName, namespace, "");
    }

    public static String associateRolesURI(String roleName, String warehouseName) {
        return format(
            "/api/management/v1/principal-roles/%s/catalog-roles/%s",
            roleName,
            warehouseName);
    }

    public static String getPrincipalToRolesURI(String principal) {
        return format(
            "/api/management/v1/principals/%s/principal-roles",
            principal);
    }

    public static String getCreateCatalogURI() {
        return "/api/management/v1/catalogs";
    }

    public static String getCatalogAdminGrantsURI(String warehouseName) {
        return format(
            "%s/%s/catalog-roles/catalog_admin/grants",
            getCreateCatalogURI(),
            warehouseName);
    }

    public static String principalRolesURI(String roleName) {
        return format("%s/%s/principal-roles", principalsURI(), roleName);
    }

    public static String principalRolesURI() {
        return "/api/management/v1/principal-roles";
    }

    public static String principalsURI() {
        return "/api/management/v1/principals";
    }

    public static String catalogRolesURI(String warehouseName) {
        return format("/api/management/v1/catalogs/%s/catalog-roles", warehouseName);
    }

    public static String catalogRolesGrantURI(String warehouseName, String catalogRole) {
        return format("/api/management/v1/catalogs/%s/catalog-roles/%s/grants", warehouseName, catalogRole);
    }

    public static String catalogsURI(String warehouseName) {
        return format(
            "%s/%s",
            getCreateCatalogURI(),
            warehouseName);
    }

    public static String namespaceURI(
        String warehouseName) {
        return format("/api/catalog/v1/%s/namespaces/", warehouseName);
    }

    public static String namespaceURI(
        String warehouseName,
        String namespace) {
        return format("%s/%s", namespaceURI(warehouseName), namespace);
    }

    public static String tableURI(
        String warehouseName,
        String namespace,
        String tableName) {
        return format(
            "/api/catalog/v1/%s/namespaces/%s/tables/%s",
            warehouseName, namespace, tableName);
    }

    public PolarisConfigurer(AfonPolarisClient client) {
        this.client = client;
    }

    public static boolean isFailure(HttpResponse response) {
        return response == null || response.getCode() / 100 != 2;
    }

    /**
     * Everything you need to initialize Polaris in one method.
     */
    public HttpResponse[] configurCatalogFor(
        CloudConfigForPolaris config,
        String accessToken) throws IOException, IncorrectConfigException {
        String warehouseName = config.warehouseName();
        client.delete(catalogsURI(warehouseName), accessToken); // this failing is expected if there is no catalog
        return new HttpResponse[]{
            client.postJsonCall(
                getCreateCatalogURI(),
                config.catalogConfig(),
                accessToken),
            client.putJsonCall(
                getCatalogAdminGrantsURI(warehouseName),
                config.getDtoMaker().catalogAdminGrants(),
                accessToken),
            };
    }

    public PrincipalWithCredentials configureUser(
        String accessToken,
        String principalName) throws IncorrectConfigException, IOException {
        String      uri  = principalsURI();
        PolarisDTOs dtos = new PolarisDTOs(new JsonMaker());
        String      json = dtos.principalCreation(principalName);
        if (isFailure(deletePrincipalIfPresent(accessToken, principalName))) {
            logger.warn("Could not delete principal {}", principalName);
        }
        logger.info(format("Creating user %s", principalName));
        try (ClassicHttpResponse response = client.postJsonCall(uri, json, accessToken);
             InputStream is = response.getEntity().getContent()) {
            return dtos.fromJson(is, PrincipalWithCredentials.class);
        }
    }

    private ClassicHttpResponse deletePrincipalIfPresent(String accessToken, String principalName) throws IOException {
        logger.info("Deleting principal {}", principalName);
        String uri = principalsURI() + "/" + principalName;
        try (ClassicHttpResponse delete = client.delete(uri, accessToken)) {
            logger.info("Deleting {} resulted in HTTP code {}", principalName, delete.getCode());
            return delete;
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
        properties = addPolarisCredentialsTo(credentials, properties);
        for (CloudConfigForPolaris config : configs) {
            logger.info("Configuring {}", config.warehouseName());
            HttpResponse[] responses = callPolaris(properties, config);
            Arrays.stream(responses).forEach(PolarisConfigurer::logIfProblem);
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
        Properties  properties = new FilePropertyProvider().getProperties();
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
        logger.info("Configuring {}", config.warehouseName());
        callPolaris(properties, config);
    }

    public HttpResponse[] callPolaris(Properties properties, CloudConfigForPolaris config) throws IOException, IncorrectConfigException {
        String protocol    = "https";
        if (POLARIS_ENDPOINT.getValueFrom(properties).toLowerCase().contains("localhost")) {
            protocol = "http";
        }
        var    client      = AfonPolarisClientFactory.clientFactory().getPolarisClient(properties, protocol);
        var    configurer  = new PolarisConfigurer(client);
        String accessToken = client.getAccessToken();
        logger.info("Access token {}", accessToken);
        return configurer.configurCatalogFor(config, accessToken);
    }

}
