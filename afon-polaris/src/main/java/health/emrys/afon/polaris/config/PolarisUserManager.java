package health.emrys.afon.polaris.config;

import health.emrys.afon.config.*;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.catalogs.PolarisSerialiser;
import org.apache.hc.core5.http.ClassicHttpResponse;
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

/**
 * Manages Polaris users, allowing the configuration of users and their catalog roles and privileges.
 *
 * Provides default {@link #DEFAULT_CATALOG_ROLE_NAME} and {@link #DEFAULT_CATALOG_PRIVILEGES}, if not specified.
 */
public class PolarisUserManager {

    private static final Logger logger = LoggerFactory.getLogger(PolarisUserManager.class);

    public static final String DEFAULT_CATALOG_ROLE_NAME = "emrysrole4";
    public static final Set<CatalogPrivilege> DEFAULT_CATALOG_PRIVILEGES =
            Set.of(NAMESPACE_CREATE, NAMESPACE_FULL_METADATA, TABLE_CREATE, TABLE_READ_DATA, TABLE_WRITE_DATA);

    public static final String principalRoleName(String catalogRoleName){
        return String.format("%sPrincipal", catalogRoleName);
    }
    private final CatalogConfigurer configurer;

    private final String accessToken;
    private final String clientId;
    private final String catalogRoleName;
    private final String principalRoleName;
    private final Set<CatalogPrivilege> privileges;

    public PolarisUserManager(AfonPropertyProvider properties) {
        this(properties, DEFAULT_CATALOG_ROLE_NAME, DEFAULT_CATALOG_PRIVILEGES);
    }

    public PolarisUserManager(AfonPropertyProvider properties, String catalogRoleName, Set<CatalogPrivilege> privileges) {
        PolarisRestClient client = PolarisRestClientFactory.clientFactory().createClient(properties);
        this.configurer = new CatalogConfigurer(client);
        this.accessToken = client.getAccessToken();
        this.clientId = client.getClientId();
        this.catalogRoleName = catalogRoleName;
        this.principalRoleName = principalRoleName(catalogRoleName);
        this.privileges = privileges;
        logger.debug("Polaris access token for admin {} when calling endpoint {}",
                clientId, client.getEndpoint());
    }

    public String getCatalogRoleName() {
        return catalogRoleName;
    }

    public String getPrincipalRoleName() {
        return principalRoleName;
    }

    public Set<CatalogPrivilege> getPrivileges() {
        return privileges;
    }

    public List<HttpResponse> configureUsers(String catalogName, AfonUserProvider userProvider) throws IncorrectConfigException, IOException {
        createCatalogRole(catalogName);
        createPrincipalRole(principalRoleName);
        List<HttpResponse> responses = new ArrayList<>();
        for (AfonUser user : userProvider.getUsers()) {
            if (userProvider.isAuthorised(user.username(), catalogName)) {
                responses.add(configureUser(user.username(), catalogName));
            } else {
                logger.warn("Admin {} failed to authorise user {} for catalog {}",
                        clientId, user.username(), catalogName);
            }
        }
        return responses;
    }

    private HttpResponse configureUser(String principalName, String catalogName) throws IncorrectConfigException, IOException {
        logger.info("Configuring user '{}' for catalog '{}'", principalName, catalogName);
        PrincipalWithCredentials principal = createPrincipal(principalName);
        logger.info("Admin {} created user {}", clientId, principal);
        configurer.associatePrincipalRole(catalogName, principalRoleName, catalogRoleName);
        return configurer.associatePrincipalRole(principalName, principalRoleName);
    }

    public List<HttpResponse> createCatalogRole(String catalogName) throws IOException, IncorrectConfigException {
        configurer.deleteCatalogRole(catalogName, catalogRoleName);
        return createCatalogRoleIfAbsent(catalogName);
    }

    /**
     * Creates the catalog role without first deleting any existing one, so an already-existing role
     * (and anything associated with it) is left untouched.
     */
    public List<HttpResponse> createCatalogRoleIfAbsent(String catalogName) throws IOException, IncorrectConfigException {
        logger.info("Creating role '{}' on catalog '{}'", catalogRoleName, catalogName);
        List<HttpResponse> failures = new ArrayList<>();
        HttpResponse response = configurer.createCatalogRole(catalogName, catalogRoleName);
        if (isFailure(response)) {
            failures.add(response);
        } else {
            logger.info("Admin {} created role {} on catalog {}", clientId, catalogRoleName, catalogName);
            failures.addAll(addPrivileges(catalogName, catalogRoleName, privileges));
        }
        return failures;
    }

    private List<HttpResponse> addPrivileges(String catalogName, String roleName, Set<CatalogPrivilege> privileges) throws IncorrectConfigException, IOException {
        logger.info("Adding privileges '{}' for role '{}' on catalog '{}'", privileges, roleName, catalogName);
        List<HttpResponse> failures = new ArrayList<>();
        for (CatalogPrivilege privilege : privileges) {
            HttpResponse response = configurer.createCatalogRoleGrant(catalogName, roleName, privilege);
            if (isFailure(response)) {
                failures.add(response);
            }
        }
        return failures;
    }

    public PrincipalWithCredentials createPrincipal(String principalName) throws IncorrectConfigException, IOException {
        configurer.deletePrincipal(principalName);
        return principalWithCredentials(configurer.createPrincipal(principalName));
    }

    public void createPrincipalRole(String principalRoleName) throws IOException, IncorrectConfigException {
        configurer.createPrincipalRole(principalRoleName);
    }

    public void associatePrincipalRole(String principalName, String principalRoleName) throws IncorrectConfigException, IOException {
        configurer.associatePrincipalRole(principalName, principalRoleName);
    }

    public HttpResponse associatePrincipalRole(String catalogName, String principalRoleName, String catalogRoleName) throws IncorrectConfigException, IOException {
        return configurer.associatePrincipalRole(catalogName, principalRoleName, catalogRoleName);
    }

    private PrincipalWithCredentials principalWithCredentials(ClassicHttpResponse response) throws IOException {
        PolarisSerialiser serialiser = new PolarisSerialiser();
        return serialiser.fromJson(response.getEntity().getContent(), PrincipalWithCredentials.class);
    }

}
