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

import static health.emrys.afon.config.AfonProperties.POLARIS_ENDPOINT;
import static health.emrys.afon.config.AfonProperties.USER_POLARIS_ID;
import static org.apache.polaris.core.admin.model.CatalogPrivilege.*;

public class ConfigureUserMain {

    private static final Logger                logger            = LoggerFactory.getLogger(ConfigureUserMain.class);
    private static final Set<CatalogPrivilege> catalogPrivileges = Set.of(TABLE_READ_DATA, TABLE_WRITE_DATA, NAMESPACE_CREATE, TABLE_CREATE, NAMESPACE_FULL_METADATA);

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
        String            roleName      = "emrysrole4";
        try {
            logger.info("Configuring Polaris User {} for catalog {} and role {}", principalName, catalogName, roleName);
            app.configurePrivilegesFor(catalogName, roleName);
            app.configureUser(principalName, roleName, catalogName);
        } catch (Exception x) {
            logger.error("Error while configuring user " + principalName, x);
        }
    }

    private final String accessToken;

    ConfigureUserMain(Properties properties) {
        AfonPolarisClient client = AfonPolarisClientFactory.clientFactory().getPolarisClient(properties);
        accessToken = client.getAccessToken();
        logger.debug("POLARIS_TOKEN={}", accessToken);
        configurer  = new PolarisConfigurer(client);
        permissions = new PermissionsConfigurer(client);
    }

    private HttpResponse configureUser(
            String principalName,
            String roleName,
            String catalogName) throws IncorrectConfigException, IOException {
        PrincipalWithCredentials principal         = configurer.configureUser(accessToken, principalName);
        String                   principalRoleName = roleName + "Principal";
        logger.info("Created Polaris User {}", principal);

        // TODO - delete first?
        logger.info("HTTP code = {}", permissions.createPrincipalRoles(principalRoleName).getCode());
        logger.info("HTTP code = {}", permissions.associateRoles(catalogName, principalRoleName, roleName));

        HttpResponse response = permissions.associatePrincipalRoles(principalName, principalRoleName);
        if (isFailure(response)) {
            logger.error("Could not create user {} with role {}. HTTP code = {}", principalName, roleName, response.getCode());
        }
        return response;
    }

    private static boolean isFailure(HttpResponse response) {
        return response.getCode() / 100 != 2;
    }

    private List<HttpResponse> configurePrivilegesFor(
        String catalogName,
        String roleName) throws IOException, IncorrectConfigException {
        permissions.deleteRoleIfExists(roleName, catalogName);
        List<HttpResponse> results  = new ArrayList<>();
        HttpResponse       response = permissions.createCatalogRole(catalogName, roleName);
        if (isFailure(response)) {
            logger.error("Could not create role {} frr catalog {}", roleName, catalogName);
            results.add(response);
        } else {
            logger.info("Created role {}", roleName);
            results.addAll(addPermissions(catalogName, roleName));
        }
        return results;
    }

    private List<HttpResponse> addPermissions(String catalogName, String roleName) throws IncorrectConfigException, IOException {
        List<HttpResponse> results = new ArrayList<>();
        for (CatalogPrivilege privilege : catalogPrivileges) {
            HttpResponse result = permissions.createCatalogRoleGrant(catalogName, privilege, roleName);
            if (isFailure(result)) {
                logger.error("Could not grant role {} the privilege {} on  catalog {}", roleName, privilege, catalogName);
            }
            results.add(result);
        }
        return results;
    }
}
