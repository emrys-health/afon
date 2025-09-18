package health.emrys.afon.polaris.comms;

import health.emrys.afon.polaris.catalogs.CloudConfigForPolaris;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;

import java.io.IOException;

import static java.lang.String.format;

public class PolarisConfigurer {

    private final AfonPolarisClient client;

    public PolarisConfigurer(AfonPolarisClient client) {
        this.client = client;
    }

    public void configurCatalogFor(CloudConfigForPolaris config, String accessToken) throws IOException, IncorrectConfigException {
        client.postJsonCall("/api/management/v1/catalogs",
                            config.catalogConfig(),
                            accessToken);
        client.putJsonCall(format("/api/management/v1/catalogs/%s/catalog-roles/catalog_admin/grants", config.warehouseName()),
                           config.catalogAdminGrants(),
                           accessToken);
        client.putJsonCall(format("/api/management/v1/principal-roles/service_admin/catalog-roles/%s", config.warehouseName()),
                           config.catalogRoles(),
                           accessToken);
    }
}
