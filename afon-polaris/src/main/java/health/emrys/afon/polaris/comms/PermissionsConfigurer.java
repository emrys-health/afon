package health.emrys.afon.polaris.comms;

import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.catalogs.JsonMaker;
import health.emrys.afon.polaris.catalogs.PolarisDTOs;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.polaris.core.admin.model.CatalogPrivilege;

import java.io.IOException;

import static health.emrys.afon.polaris.comms.PolarisConfigurer.*;

public class PermissionsConfigurer {

    private final PolarisDTOs dtos = new PolarisDTOs(new JsonMaker());

    private final AfonPolarisClient client;
    private final String accessToken;

    public PermissionsConfigurer(AfonPolarisClient client) {
        this.client = client;
        accessToken = client.getAccessToken();
    }

    public HttpResponse associateRoles(String warehouseName,
                                       String principalRoleName,
                                       String catalogRoleName) throws IOException, IncorrectConfigException {
        String uri = associateRolesURI(principalRoleName, warehouseName);
        String json = dtos.catalogRoleCreation(catalogRoleName);
        return client.putJsonCall(uri, json, accessToken);
    }

    public HttpResponse createCatalogRoleGrant(String warehouseName,
                                               CatalogPrivilege perm,
                                               String catalogRoleName) throws IncorrectConfigException, IOException {
        String uri = catalogRolesGrantURI(warehouseName, catalogRoleName);
        String json = dtos.catalogRoleGrant(perm);
        return client.putJsonCall(uri, json, accessToken);
    }

    public HttpResponse createCatalogRole(String warehouseName,
                                          String catalogRoleName) throws IOException, IncorrectConfigException {
        String uri = catalogRolesURI(warehouseName);
        String json = dtos.catalogRoleCreation(catalogRoleName);
        return client.postJsonCall(uri, json, accessToken);
    }
}
