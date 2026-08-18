package health.emrys.afon.polaris.config;

import health.emrys.afon.polaris.catalogs.CloudConfigForPolaris;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.catalogs.PolarisSerialiser;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.polaris.core.admin.model.PrincipalWithCredentials;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import static java.lang.String.format;

public class PolarisConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(PolarisConfigurer.class);

    private final PolarisRestClient client;

    public static String catalogsURI() {
        return "/api/management/v1/catalogs";
    }

    public static String catalogsURI(String warehouseName) {
        return format(
                "%s/%s",
                catalogsURI(),
                warehouseName);
    }

    public static String catalogRolesURI(String warehouseName) {
        return format("/api/management/v1/catalogs/%s/catalog-roles", warehouseName);
    }

    public static String catalogeRolesURI(String roleName, String warehouseName) {
        return format(
                "/api/management/v1/principal-roles/%s/catalog-roles/%s",
                roleName,
                warehouseName);
    }

    public static String catalogRolesGrantURI(String warehouseName, String catalogRole) {
        return format("/api/management/v1/catalogs/%s/catalog-roles/%s/grants", warehouseName, catalogRole);
    }

    public static String catalogRolesAdminGrantsURI(String warehouseName) {
        return format(
                "%s/%s/catalog-roles/catalog_admin/grants",
                catalogsURI(),
                warehouseName);
    }

    public static String principalsURI() {
        return "/api/management/v1/principals";
    }

    public static String principalRolesURI() {
        return "/api/management/v1/principal-roles";
    }

    public static String principalRolesURI(String principal) {
        return format(
                "/api/management/v1/principals/%s/principal-roles",
                principal);
    }

    public static String namespacesURI(
            String warehouseName) {
        return format("/api/catalog/v1/%s/namespaces/", warehouseName);
    }

    public static String namespaceURI(
            String warehouseName,
            String namespace) {
        return format("%s/%s", namespacesURI(warehouseName), namespace);
    }

    public static String tablesURI(String warehouseName, String namespace) {
        return tableURI(warehouseName, namespace, "");
    }

    public static String tableURI(
            String warehouseName,
            String namespace,
            String tableName) {
        return format(
                "/api/catalog/v1/%s/namespaces/%s/tables/%s",
                warehouseName, namespace, tableName);
    }

    public PolarisConfigurer(PolarisRestClient client) {
        this.client = client;
    }

    public static boolean isFailure(HttpResponse response) {
        return response == null || response.getCode() / 100 != 2;
    }

    public static @NonNull String tokensURI(String endpoint) {
        return format("%s/api/catalog/v1/oauth/tokens", endpoint);
    }

    public HttpResponse[] configureCatalog(CloudConfigForPolaris config) throws IOException, IncorrectConfigException {
        String accessToken = client.getAccessToken();
        logger.info("Access token {}", accessToken);
        String warehouseName = config.warehouseName();
        client.delete(catalogsURI(warehouseName), accessToken); // this failing is expected if there is no catalog
        HttpResponse[] responses = new HttpResponse[]{
                client.post(
                        catalogsURI(),
                        config.catalogConfig(),
                        accessToken),
                client.put(
                        catalogRolesAdminGrantsURI(warehouseName),
                        config.getSerialiser().catalogAdminGrants(),
                        accessToken),
        };
        logIfProblems(responses);
        return responses;
    }

    public PrincipalWithCredentials configureUser(
            String accessToken,
            String principalName) throws IncorrectConfigException, IOException {
        String uri = principalsURI();
        PolarisSerialiser serialiser = new PolarisSerialiser();
        String json = serialiser.principalCreation(principalName);
        if (isFailure(deletePrincipalIfPresent(accessToken, principalName))) {
            logger.warn("Could not delete principal {}", principalName);
        }
        logger.info(format("Creating user %s", principalName));
        try (ClassicHttpResponse response = client.post(uri, json, accessToken);
             InputStream is = response.getEntity().getContent()) {
            return serialiser.fromJson(is, PrincipalWithCredentials.class);
        }
    }

    private HttpResponse deletePrincipalIfPresent(String accessToken, String principalName) throws IOException {
        logger.info("Deleting principal {}", principalName);
        String uri = principalsURI() + "/" + principalName;
        try (ClassicHttpResponse response = client.delete(uri, accessToken)) {
            logger.info("Deleting {} resulted in HTTP code {}", principalName, response.getCode());
            logIfProblem(response);
            return response;
        }
    }

    private static void logIfProblems(HttpResponse[] responses) {
        Arrays.stream(responses).forEach(PolarisConfigurer::logIfProblem);
    }

    private static void logIfProblem(HttpResponse response) {
        if (response.getCode() / 100 != 2) {
            logger.error("HTTP code {}. Error: {}", response.getCode(), response.getReasonPhrase());
        }
    }


}
