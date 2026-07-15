package health.emrys.afon.polaris.comms;

import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.polaris.core.admin.model.CatalogPrivilege;
import org.apache.polaris.core.admin.model.PrincipalWithCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.config.AfonProperties.POLARIS_SECRET;
import static java.lang.String.format;
import static org.apache.polaris.core.admin.model.CatalogPrivilege.*;

public class ConfigureUserMain {

    public static final String ROLE_NAME = "emrysrole4";

    private static final Logger                logger            = LoggerFactory.getLogger(ConfigureUserMain.class);
    private static final Set<CatalogPrivilege> catalogPrivileges = Set.of(TABLE_READ_DATA,
                                                                          TABLE_WRITE_DATA,
                                                                          NAMESPACE_CREATE,
                                                                          TABLE_CREATE,
                                                                          NAMESPACE_FULL_METADATA);

    private final PolarisConfigurer     configurer;
    private final PermissionsConfigurer permissions;

    public static void main(String[] args) {
        Properties properties = new AfonPropertiesLoader().getProperties();
        if (args.length > 0) {
            String endpoint = args[0];
            logger.info("Using endpoint {}", endpoint);
            properties.put(POLARIS_ENDPOINT.getPropertyName(), endpoint);
        }
        ConfigureUserMain app           = new ConfigureUserMain(properties);
        String            catalogName   = args[1];
        String            principalName = args.length > 2 ? args[2] : USER_POLARIS_ID.getValueFrom(properties);
        try {
            logger.info("Configuring Polaris user '{}' for catalog '{}' and role '{}'",
                        principalName,
                        catalogName,
                        ROLE_NAME);
            app.configurePrivilegesFor(catalogName, ROLE_NAME);
            app.configureUser(principalName, ROLE_NAME, catalogName);
        } catch (Exception x) {
            logger.error("Error while configuring user " + principalName, x);
        }
    }

    private final String accessToken;
    private final String adminClientId;

    public ConfigureUserMain(Properties properties) {
        AfonPolarisClient client = AfonPolarisClientFactory.clientFactory().getPolarisClient(
            POLARIS_ENDPOINT.getValueFrom(properties),
            properties); // For service-account-root/s3cr3t: POLARIS_TOKEN=null
        accessToken   = client.getAccessToken();
        configurer    = new PolarisConfigurer(client);
        permissions   = new PermissionsConfigurer(client);
        adminClientId = client.getClientId();
        logger.debug("For {}/{}:\nPOLARIS_TOKEN={}", client.getClientId(), client.getClientSecret(), accessToken);
    }

    public HttpResponse configureUser(
        String principalName,
        String roleName,
        String catalogName
    ) throws IncorrectConfigException, IOException {
        PrincipalWithCredentials principal         = configurer.configureUser(accessToken, principalName);
        String                   principalRoleName = roleName + "Principal";
        logger.info(
            "Admin '{}' creating Polaris User {}",
            adminClientId,
            principal);

        // A previous run may have left this principal role — and hence its catalog-role association —
        // in place, which would make the create/associate below fail. Delete it first so both start
        // from a clean slate; removing the principal role also drops its catalog-role association.
        if (isFailure(permissions.deletePrincipalRoleIfExists(principalRoleName))) {
            logger.warn("Admin {} could not delete principal role {}", adminClientId, principalRoleName);
        }
        if (isFailure(permissions.createPrincipalRoles(principalRoleName))) {
            logger.warn("Admin {} failed to create principal role {}", adminClientId, principalRoleName);
        }
        if (isFailure(permissions.associateRoles(catalogName, principalRoleName, roleName))) {
            logger.warn("Admin {} failed to associate role {} with principale role {} in catalog {}",
                        adminClientId, roleName, principalRoleName, catalogName);
        }

        HttpResponse response = permissions.associatePrincipalRoles(principalName, principalRoleName);
        if (isFailure(response)) {
            logger.error("Could not create user {} with role {}. HTTP code = {}", principalName, roleName, response.getCode());
        }
        return response;
    }

    private static boolean isFailure(HttpResponse response) {
        return response.getCode() / 100 != 2;
    }

    public List<HttpResponse> configurePrivilegesFor(
        String catalogName,
        String roleName
    ) throws IOException, IncorrectConfigException {
        permissions.deleteRoleIfExists(roleName, catalogName);
        List<HttpResponse> results  = new ArrayList<>();
        HttpResponse       response = permissions.createCatalogRole(catalogName, roleName);
        if (isFailure(response)) {
            logger.error("Admin {} could not create role {} for catalog {}", adminClientId, roleName, catalogName);
            results.add(response);
        } else {
            logger.info("Admin {} created role {}", adminClientId, roleName);
            results.addAll(addPermissions(catalogName, roleName));
        }
        return results;
    }

    private List<HttpResponse> addPermissions(String catalogName, String roleName) throws IncorrectConfigException, IOException {
        List<HttpResponse> results = new ArrayList<>();
        for (CatalogPrivilege privilege : catalogPrivileges) {
            HttpResponse result = permissions.createCatalogRoleGrant(catalogName, privilege, roleName);
            if (isFailure(result)) {
                logger.error("Admin '{}' ould not grant role {} the privilege {} on  catalog {}",
                             adminClientId,
                             roleName,
                             privilege,
                             catalogName);
            }
            results.add(result);
        }
        return results;
    }
}
