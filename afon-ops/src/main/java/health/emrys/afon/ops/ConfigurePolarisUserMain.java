package health.emrys.afon.ops;

import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.config.AfonPolarisClient;
import health.emrys.afon.polaris.config.AfonPolarisClientFactory;
import health.emrys.afon.polaris.config.PermissionsConfigurer;
import health.emrys.afon.polaris.config.PolarisConfigurer;
import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.AfonUserProvider;
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

import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;
import static health.emrys.afon.config.AfonProperty.USER_POLARIS_ID;
import static org.apache.polaris.core.admin.model.CatalogPrivilege.*;

public class ConfigurePolarisUserMain {

    public static final String ROLE_NAME = "emrysrole4";

    private static final Logger                logger            = LoggerFactory.getLogger(ConfigurePolarisUserMain.class);
    private static final Set<CatalogPrivilege> catalogPrivileges = Set.of(
        TABLE_READ_DATA,
        TABLE_WRITE_DATA,
        NAMESPACE_CREATE,
        TABLE_CREATE,
        NAMESPACE_FULL_METADATA);

    private final PolarisConfigurer configurer;
    private final PermissionsConfigurer permissions;

    private final String accessToken;
    private final String adminClientId;
    private final String roleName;
    private final String principalRoleName;

    public ConfigurePolarisUserMain(Properties properties) {
        AfonPolarisClient client = AfonPolarisClientFactory.clientFactory().getPolarisClient(
            POLARIS_ENDPOINT.getValueFrom(properties),
            properties);
        accessToken       = client.getAccessToken();
        configurer        = new PolarisConfigurer(client);
        permissions       = new PermissionsConfigurer(client);
        adminClientId     = client.getClientId();
        roleName          = ROLE_NAME;
        principalRoleName = roleName + "Principal";
        logger.debug("For {}/{} when calling {}:\nPOLARIS_TOKEN={}",
                     client.getClientId(),
                     client.getClientSecret(),
                     client.getEndpoint(),
                     accessToken);
    }

    public void configureAll(String warehouse,
                             AfonUserProvider userProvider
    ) throws IncorrectConfigException, IOException {
        // A previous run may have left this principal role — and hence its catalog-role association —
        // in place, which would make the create/associate below fail. Delete it first so both start
        // from a clean slate; removing the principal role also drops its catalog-role association.
        if (PolarisConfigurer.isFailure(permissions.deletePrincipalRoleIfExists(principalRoleName))) {
            logger.warn("Admin {} could not delete principal role {}", adminClientId, principalRoleName);
        }
        if (PolarisConfigurer.isFailure(permissions.createPrincipalRoles(principalRoleName))) {
            logger.warn("Admin {} failed to create principal role {}", adminClientId, principalRoleName);
        }
        for (AfonUser user : userProvider.getUsers()) {
            configureUser(user.username(), warehouse);
        }
    }

    private HttpResponse configureUser(
        String principalName,
        String catalogName
    ) throws IncorrectConfigException, IOException {
        logger.info(
            "Configuring Polaris user '{}' for catalog '{}'",
            principalName,
            catalogName);
        PrincipalWithCredentials principal = configurer.configureUser(accessToken, principalName);
        logger.info(
            "Admin '{}' created Polaris User {}",
            adminClientId,
            principal);

        if (PolarisConfigurer.isFailure(permissions.associateRoles(catalogName, principalRoleName, roleName))) {
            logger.warn(
                "Admin {} failed to associate role {} with principale role {} in catalog {}",
                adminClientId, roleName, principalRoleName, catalogName);
        }

        HttpResponse response = permissions.associatePrincipalRoles(principalName, principalRoleName);
        if (PolarisConfigurer.isFailure(response)) {
            logger.error("Could not create user {} with role {}. HTTP code = {}", principalName, roleName, response.getCode());
        }
        return response;
    }

    public List<HttpResponse> configurePrivilegesFor(
        String catalogName
    ) throws IOException, IncorrectConfigException {
        logger.info(
            "Configuring Polaris  catalog '{}' and role '{}'",
            catalogName,
            roleName);
        if (PolarisConfigurer.isFailure(permissions.deleteRoleIfExists(roleName, catalogName))) {
            logger.warn("Admin {} could not delete role {} from catalog", adminClientId, roleName, catalogName);
        }
        List<HttpResponse> results  = new ArrayList<>();
        HttpResponse       response = permissions.createCatalogRole(catalogName, roleName);
        if (PolarisConfigurer.isFailure(response)) {
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
            if (PolarisConfigurer.isFailure(result)) {
                logger.error(
                    "Admin '{}' ould not grant role {} the privilege {} on  catalog {}",
                    adminClientId,
                    roleName,
                    privilege,
                    catalogName);
            }
            results.add(result);
        }
        return results;
    }

    public static void main(String[] args) {
        String endpoint = null;
        String catalogName = null;
        if (args.length > 1) {
            endpoint = args[0];
            catalogName = args[1];
            logger.info("Using endpoint {} and catalog name {}", endpoint, catalogName);
        }
        String principalName = null;
        if (args.length > 2){
            principalName = args[2];
        }
        Properties properties = new FilePropertyProvider().getProperties();
        if (principalName == null) {
            principalName = USER_POLARIS_ID.getValueFrom(properties);
        }
        if ( endpoint != null ) {
            properties.put(POLARIS_ENDPOINT.getName(), endpoint);
        }
        ConfigurePolarisUserMain app = new ConfigurePolarisUserMain(properties);
        try {
            app.configurePrivilegesFor(catalogName);
            app.configureUser(principalName, catalogName);
        } catch (Exception e) {
            logger.error("Error configuring user " + principalName, e);
        }
    }

}
