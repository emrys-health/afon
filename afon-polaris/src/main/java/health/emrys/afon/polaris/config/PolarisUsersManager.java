package health.emrys.afon.polaris.config;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.polaris.core.admin.model.CatalogPrivilege;
import org.apache.polaris.core.admin.model.PrincipalWithCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static health.emrys.afon.polaris.config.PolarisRestClient.isFailure;
import static org.apache.polaris.core.admin.model.CatalogPrivilege.*;

public class PolarisUsersManager {

    public static final String ROLE_NAME = "emrysrole4";

    private static final Logger logger = LoggerFactory.getLogger(PolarisUsersManager.class);
    private static final Set<CatalogPrivilege> CATALOG_PRIVILEGES = Set.of(
            NAMESPACE_CREATE,
            NAMESPACE_FULL_METADATA,
            TABLE_CREATE,
            TABLE_READ_DATA,
            TABLE_WRITE_DATA);

    private final PolarisConfigurer configurer;
    private final PermissionsConfigurer permissions;

    private final String accessToken;
    private final String adminClientId;
    private final String endpoint;
    private final String roleName;
    private final String principalRoleName;

    public PolarisUsersManager(AfonPropertyProvider properties) {
        PolarisRestClient client = PolarisRestClientFactory.clientFactory().createClient(properties);
        accessToken       = client.getAccessToken();
        adminClientId     = client.getClientId();
        endpoint          = client.getEndpoint();
        configurer        = new PolarisConfigurer(client);
        permissions       = new PermissionsConfigurer(client);
        roleName          = ROLE_NAME;
        principalRoleName = roleName + "Principal";
        logger.debug("Polaris access token {} for user {} when calling endpoint {}",
                accessToken, adminClientId, endpoint);
    }

    public List<HttpResponse> configureUsers(String catalogName,
                                             AfonUserProvider userProvider
    ) throws IncorrectConfigException, IOException {
        List<HttpResponse> responses = new ArrayList<>();
        if (isFailure(permissions.createPrincipalRoles(principalRoleName))) {
            logger.warn("Admin {} failed to create principal role {}. May already exist",
                        adminClientId, principalRoleName);
        }
        for (AfonUser user : userProvider.getUsers()) {
            // check if catalogName is authorised for user
            if ( userProvider.isAuthorised(user.username(), catalogName) ) {
                responses.add(configureUser(user.username(), catalogName));
            } else {
                logger.warn("Admin {} could not authorise user {} for catalog {}", adminClientId, user.username(), catalogName);
            }
        }
        return responses;
    }

    private HttpResponse configureUser(
        String principalName,
        String catalogName
    ) throws IncorrectConfigException, IOException {
        logger.info("Configuring Polaris user '{}' for catalog '{}'", principalName, catalogName);
        PrincipalWithCredentials principal = configurer.configureUser(accessToken, principalName);
        logger.info("Admin '{}' created Polaris user {}", adminClientId, principal);

        if (isFailure(permissions.associateRoles(catalogName, principalRoleName, roleName))) {
            logger.warn(
                "Admin {} failed to associate role {} with principale role {} in catalog {}",
                adminClientId, roleName, principalRoleName, catalogName);
        }

        HttpResponse response = permissions.associatePrincipalRoles(principalName, principalRoleName);
        if (isFailure(response)) {
            logger.error("Could not create user {} with role {}. HTTP code = {}", principalName, roleName, response.getCode());
        }
        return response;
    }

    public List<HttpResponse> configureCatalog(String catalogName) throws IOException, IncorrectConfigException {
        logger.info("Configuring Polaris catalog '{}' and role '{}'", catalogName, roleName);
        if (isFailure(permissions.deleteRoleIfExists(roleName, catalogName))) {
            logger.warn("Admin {} could not delete role {} from catalog", adminClientId, roleName, catalogName);
        }
        List<HttpResponse> results = new ArrayList<>();
        HttpResponse response = permissions.createCatalogRole(catalogName, roleName);
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
        for (CatalogPrivilege privilege : CATALOG_PRIVILEGES) {
            HttpResponse result = permissions.createCatalogRoleGrant(catalogName, privilege, roleName);
            if (isFailure(result)) {
                logger.error("Admin '{}' could not grant role {} the privilege {} on  catalog {}",
                            adminClientId, roleName, privilege, catalogName);
            }
            results.add(result);
        }
        return results;
    }

}
