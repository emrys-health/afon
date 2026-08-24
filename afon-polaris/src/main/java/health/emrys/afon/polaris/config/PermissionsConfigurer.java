package health.emrys.afon.polaris.config;

import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.catalogs.PolarisSerialiser;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.polaris.core.admin.model.CatalogPrivilege;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static health.emrys.afon.polaris.config.PolarisConfigurer.*;

public class PermissionsConfigurer {
    private static final Logger logger = LoggerFactory.getLogger(PermissionsConfigurer.class);

    private final PolarisSerialiser serialiser = new PolarisSerialiser();

    private final PolarisRestClient client;
    private final String            accessToken;

    public PermissionsConfigurer(PolarisRestClient client) {
        this.client = client;
        accessToken = client.getAccessToken();
    }

    public HttpResponse associateRoles(
        String catalogName,
        String principalRoleName,
        String catalogRoleName) throws IOException, IncorrectConfigException {
        logger.info("Associating {} with catalog role {} in catalog {}", principalRoleName, catalogRoleName, catalogName);
        String uri  = catalogeRolesURI(principalRoleName, catalogName);
        String json = serialiser.catalogRoleCreation(catalogRoleName);
        return client.put(uri, json, accessToken);
    }

    public HttpResponse createCatalogRoleGrant(
        String catalogName,
        CatalogPrivilege privilege,
        String catalogRoleName) throws IncorrectConfigException, IOException {
        String uri  = catalogRolesGrantURI(catalogName, catalogRoleName);
        String json = serialiser.catalogRoleGrant(privilege);
        return client.put(uri, json, accessToken);
    }

    public HttpResponse createCatalogRole(
        String catalogName,
        String catalogRoleName) throws IOException, IncorrectConfigException {
        String uri = catalogRolesURI(catalogName);
        String json = serialiser.catalogRoleCreation(catalogRoleName);
        return client.post(uri, json, accessToken);
    }

    public HttpResponse deleteRoleIfExists(String role, String catalogName) throws IOException {
        String       uri      = catalogRolesURI(catalogName) + "/" + role;
        HttpResponse response = client.delete(uri, accessToken);
        logger.info("Deleting role {} for catalog {}: HTTP code = {}", role, catalogName, response.getCode());
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
        String json = serialiser.principalRoleCreation(principalRoleName);
        return client.post(uri, json, accessToken);
    }

    public HttpResponse associatePrincipalRoles(
        String principalName,
        String principalRoleName) throws IncorrectConfigException, IOException {
        logger.info("Associated user {} with role {}", principalName, principalRoleName);
        String uri  = PolarisConfigurer.principalRolesURI(principalName);
        String json = serialiser.principalRoleCreation(principalRoleName);
        return client.put(uri, json, accessToken);
    }
}
