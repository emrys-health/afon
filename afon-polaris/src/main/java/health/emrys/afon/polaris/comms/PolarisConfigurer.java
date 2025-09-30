package health.emrys.afon.polaris.comms;

import health.emrys.afon.polaris.catalogs.CloudConfigForPolaris;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import org.apache.hc.core5.http.HttpResponse;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

import static java.lang.String.format;

public class PolarisConfigurer {

    private final AfonPolarisClient client;

    public static @NotNull String getCatalogRolesURI(String warehouseName) {
        return format("/api/management/v1/principal-roles/service_admin/catalog-roles/%s", warehouseName);
    }

    public static @NotNull String getCreateCatalogURI() {
        return "/api/management/v1/catalogs";
    }

    public static @NotNull String getCatalogAdminGrantsURI(String warehouseName) {
        return format("/api/management/v1/catalogs/%s/catalog-roles/catalog_admin/grants", warehouseName);
    }

    public PolarisConfigurer(AfonPolarisClient client) {
        this.client = client;
    }

    /**
     * Everything you need to initialize Polaris in one method.
     */
    public HttpResponse[] configurCatalogFor(CloudConfigForPolaris config, String accessToken) throws IOException, IncorrectConfigException {
        String warehouseName = config.warehouseName();
        return new HttpResponse[]{
                client.postJsonCall(getCreateCatalogURI(),
                                    config.catalogConfig(),
                                    accessToken),
                client.putJsonCall(getCatalogAdminGrantsURI(warehouseName),
                                   config.catalogAdminGrants(),
                                   accessToken),
                client.putJsonCall(getCatalogRolesURI(warehouseName),
                                   config.catalogRoles(),
                                   accessToken)
        };
    }
}
