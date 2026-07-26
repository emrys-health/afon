package health.emrys.afon.polaris.config;

import health.emrys.afon.polaris.catalogs.CloudConfigForPolaris;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.catalogs.JsonMaker;
import health.emrys.afon.polaris.catalogs.PolarisDTOs;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.polaris.core.admin.model.PrincipalWithCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

import static health.emrys.afon.polaris.config.ConfigureUserMain.isFailure;
import static java.lang.String.format;

public class PolarisConfigurer {

    private static final Logger log = LoggerFactory.getLogger(PolarisConfigurer.class);

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
            log.warn("Could not delete principal {}", principalName);
        }
        log.info(format("Creating user %s", principalName));
        try (ClassicHttpResponse response = client.postJsonCall(uri, json, accessToken);
             InputStream is = response.getEntity().getContent()) {
            return dtos.fromJson(is, PrincipalWithCredentials.class);
        }
    }

    private ClassicHttpResponse deletePrincipalIfPresent(String accessToken, String principalName) throws IOException {
        log.info("Deleting principal {}", principalName);
        String uri = principalsURI() + "/" + principalName;
        try (ClassicHttpResponse delete = client.delete(uri, accessToken)) {
            log.info("Deleting {} resulted in HTTP code {}", principalName, delete.getCode());
            return delete;
        }
    }
}
