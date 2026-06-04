package health.emrys.afon.polaris.comms;

import health.emrys.afon.polaris.catalogs.CloudConfigForPolaris;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import org.apache.hc.core5.http.HttpResponse;

import java.io.IOException;

import static java.lang.String.format;

public class PolarisConfigurer {

    private final AfonPolarisClient client;

    public static String listTables(String warehouseName, String namespace) {
        return tableURI(warehouseName, namespace, "");
    }

    public static String getCatalogRolesURI(String roleName, String warehouseName) {
        return format("/api/management/v1/principal-roles/%s/catalog-roles/%s",
                      roleName,
                      warehouseName);
    }

    public static String getCreateCatalogURI() {
        return "/api/management/v1/catalogs";
    }

    public static String getCatalogAdminGrantsURI(String warehouseName) {
        return format("%s/%s/catalog-roles/catalog_admin/grants",
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
        return format("%s/%s",
                      getCreateCatalogURI(),
                      warehouseName);
    }
    public static String namespaceURI(String warehouseName,
                                      String namespace) {
        return format("/api/catalog/v1/%s/namespaces/%s", warehouseName, namespace);
    }
    public static String tableURI(String warehouseName,
                                  String namespace,
                                  String tableName) {
        return format("/api/catalog/v1/%s/namespaces/%s/tables/%s",
                      warehouseName, namespace, tableName);
    }

    public PolarisConfigurer(AfonPolarisClient client) {
        this.client = client;
    }

    /**
     * Everything you need to initialize Polaris in one method.
     */
    public HttpResponse[] configurCatalogFor(CloudConfigForPolaris config, String accessToken) throws IOException, IncorrectConfigException {
        String warehouseName = config.warehouseName();
        client.delete(catalogsURI(warehouseName), accessToken); // this failing is expected if there is no catalog
        return new HttpResponse[]{
                client.postJsonCall(getCreateCatalogURI(),
                                    config.catalogConfig(),
                                    accessToken),
                client.putJsonCall(getCatalogAdminGrantsURI(warehouseName),
                                   config.catalogAdminGrants(),
                                   accessToken),
        };
    }
}
