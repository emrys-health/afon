package health.emrys.afon.polaris.config;

import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.catalogs.JsonMaker;
import health.emrys.afon.polaris.catalogs.PolarisDTOs;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.polaris.core.admin.model.CatalogPrivilege;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static health.emrys.afon.polaris.config.PolarisConfigurer.*;

public class PermissionsConfigurer {
    private static final Logger logger = LoggerFactory.getLogger(PermissionsConfigurer.class);

    private final PolarisDTOs dtos = new PolarisDTOs(new JsonMaker());

    private final AfonPolarisClient client;
    private final String            accessToken;

    public PermissionsConfigurer(AfonPolarisClient client) {
        this.client = client;
        accessToken = client.getAccessToken();
    }

    public HttpResponse associateRoles(
        String warehouseName,
        String principalRoleName,
        String catalogRoleName) throws IOException, IncorrectConfigException {
        logger.info("Associating {} with catalog role {} in catalog {}", principalRoleName, catalogRoleName, warehouseName);
        String uri  = associateRolesURI(principalRoleName, warehouseName);
        String json = dtos.catalogRoleCreation(catalogRoleName);
        return client.putJsonCall(uri, json, accessToken);
    }

    public HttpResponse createCatalogRoleGrant(
        String warehouseName,
        CatalogPrivilege perm,
        String catalogRoleName) throws IncorrectConfigException, IOException {
        String uri  = catalogRolesGrantURI(warehouseName, catalogRoleName);
        String json = dtos.catalogRoleGrant(perm);
        return client.putJsonCall(uri, json, accessToken);
    }

    public HttpResponse createCatalogRole(
        String warehouseName,
        String catalogRoleName) throws IOException, IncorrectConfigException {
        String uri = catalogRolesURI(warehouseName);
        String json = dtos.catalogRoleCreation(catalogRoleName);
        return client.postJsonCall(uri, json, accessToken);
    }

    public HttpResponse deleteRoleIfExists(String role, String warehouseName) throws IOException {
        String       uri      = catalogRolesURI(warehouseName) + "/" + role;
        HttpResponse response = client.delete(uri, accessToken);
        logger.info("Deleting role {} for {}: HTTP code = {}", role, warehouseName, response.getCode());
        return response;
    }

    /**
     * Deletes the principal role if it is present, returning a 404 harmlessly when it is not. Polaris
     * cascades this: removing a principal role also drops its associations with catalog roles, so this
     * is enough to clear a stale role left by a previous run before it is recreated.
     */
    public HttpResponse deletePrincipalRoleIfExists(String principalRoleName) throws IOException {
        String       uri      = principalRolesURI() + "/" + principalRoleName;
        HttpResponse response = client.delete(uri, accessToken);
        logger.info("Deleting principal role {}: HTTP code = {}", principalRoleName, response.getCode());
        return response;
    }

    public HttpResponse createPrincipalRoles(String principalRoleName) throws IOException, IncorrectConfigException {
        logger.info("Creating principal role {}", principalRoleName);
        String uri  = principalRolesURI();
        String json = dtos.principalRoleCreation(principalRoleName);
        return client.postJsonCall(uri, json, accessToken);
    }

    public HttpResponse associatePrincipalRoles(
        String principalName,
        String principalRoleName) throws IncorrectConfigException, IOException {
        logger.info("Associated user {} with role {}", principalName, principalRoleName);
        String uri  = getPrincipalToRolesURI(principalName);
        String json = dtos.principalRoleCreation(principalRoleName);
        return client.putJsonCall(uri, json, accessToken);
    }
}
