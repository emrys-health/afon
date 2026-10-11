package health.emrys.afon.polaris.config;

import health.emrys.afon.config.*;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.catalogs.PolarisSerialiser;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.polaris.core.admin.model.PrincipalWithCredentials;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.polaris.core.admin.model.CatalogPrivilege;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static health.emrys.afon.rest.HttpResponses.isFailure;
import static org.apache.polaris.core.admin.model.CatalogPrivilege.*;

/**
 * Manages Polaris users, allowing the configuration of users and their catalog roles and privileges.
 *
 * Provides default {@link #DEFAULT_CATALOG_ROLE_NAME} and {@link #DEFAULT_CATALOG_PRIVILEGES}, if not specified.
 *
 * <p>Each user has their own principal role (see {@link #userPrincipalRoleName}), which is granted the
 * catalog role of only the catalogs the user is authorised for, so that a catalog authorised for one
 * user is not visible to the others.
 */
public class PolarisUserManager {

    private static final Logger logger = LoggerFactory.getLogger(PolarisUserManager.class);

    public static final String DEFAULT_CATALOG_ROLE_NAME = "emrysrole4";
    public static final Set<CatalogPrivilege> DEFAULT_CATALOG_PRIVILEGES =
            Set.of(NAMESPACE_CREATE, NAMESPACE_FULL_METADATA, TABLE_CREATE, TABLE_READ_DATA, TABLE_WRITE_DATA);

    /**
     * The principal role of a user, granted the catalog roles of the catalogs the user is authorised for.
     */
    public static String userPrincipalRoleName(String username) {
        return String.format("%sPrincipal", username);
    }

    /**
     * The principal role formerly shared by all users, which gave every user the catalogs authorised
     * for any of them. Removed when configuring users, so that its grants don't linger.
     */
    static String sharedPrincipalRoleName(String catalogRoleName) {
        return String.format("%sPrincipal", catalogRoleName);
    }

    private final CatalogConfigurer configurer;

    private final String clientId;
    private final String catalogRoleName;
    private final Set<CatalogPrivilege> privileges;
    private boolean sharedPrincipalRoleRemoved;

    public PolarisUserManager(AfonPropertyProvider properties) {
        this(properties, DEFAULT_CATALOG_ROLE_NAME, DEFAULT_CATALOG_PRIVILEGES);
    }

    public PolarisUserManager(AfonPropertyProvider properties, String catalogRoleName, Set<CatalogPrivilege> privileges) {
        this(PolarisRestClientFactory.clientFactory().createClient(properties), catalogRoleName, privileges);
    }

    private PolarisUserManager(PolarisRestClient client, String catalogRoleName, Set<CatalogPrivilege> privileges) {
        this(new CatalogConfigurer(client), client.getClientId(), catalogRoleName, privileges);
        logger.info("Managing Polaris users as admin '{}' at endpoint '{}'",
                clientId, client.getEndpoint());
    }

    PolarisUserManager(CatalogConfigurer configurer, String clientId, String catalogRoleName, Set<CatalogPrivilege> privileges) {
        this.configurer = configurer;
        this.clientId = clientId;
        this.catalogRoleName = catalogRoleName;
        this.privileges = privileges;
    }

    public String getCatalogRoleName() {
        return catalogRoleName;
    }

    public Set<CatalogPrivilege> getPrivileges() {
        return privileges;
    }

    /**
     * Grants the catalog role of catalogName to the principal role of each user authorised for it.
     * The catalog role is recreated first, which drops its grants to any user no longer authorised.
     */
    public List<HttpResponse> configureUsers(String catalogName, AfonUserProvider userProvider) throws IncorrectConfigException, IOException {
        removeSharedPrincipalRole();
        createCatalogRole(catalogName);
        List<HttpResponse> responses = new ArrayList<>();
        for (AfonUser user : userProvider.getUsers()) {
            if (userProvider.isAuthorised(user.username(), catalogName)) {
                responses.add(configureUser(user.username(), catalogName));
            } else {
                logger.warn("Admin '{}' failed to authorise user '{}' for catalog '{}'",
                        clientId, user.username(), catalogName);
            }
        }
        return responses;
    }

    private HttpResponse configureUser(String principalName, String catalogName) throws IncorrectConfigException, IOException {
        logger.info("Configuring user '{}' for catalog '{}'", principalName, catalogName);
        createPrincipal(principalName);
        // Not recreated, unlike the catalog role: it holds the user's grants on their other catalogs.
        String userPrincipalRoleName = userPrincipalRoleName(principalName);
        createPrincipalRole(userPrincipalRoleName);
        configurer.associatePrincipalRole(catalogName, userPrincipalRoleName, catalogRoleName);
        return configurer.associatePrincipalRole(principalName, userPrincipalRoleName);
    }

    /**
     * Deletes the principal role formerly shared by all users, once per instance. Polaris cascades the
     * deletion to the role's grants and assignments.
     */
    private void removeSharedPrincipalRole() throws IOException {
        if (!sharedPrincipalRoleRemoved) {
            configurer.deletePrincipalRole(sharedPrincipalRoleName(catalogRoleName));
            sharedPrincipalRoleRemoved = true;
        }
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
            logger.info("Admin '{}' created role '{}' on catalog '{}'", clientId, catalogRoleName, catalogName);
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

    /**
     * Creates the principal if absent. An existing one is kept, with its credentials, rather than recreated
     * for every catalog the user is authorised for.
     */
    public void createPrincipal(String principalName) throws IncorrectConfigException, IOException {
        configurer.createPrincipal(principalName);
    }

    /**
     * Deletes the principal if present and creates it anew, returning its new credentials, which Polaris only
     * gives on creation. Rotates the credentials of an existing principal, so use only where that is wanted.
     */
    public PrincipalWithCredentials recreatePrincipal(String principalName) throws IncorrectConfigException, IOException {
        configurer.deletePrincipal(principalName);
        ClassicHttpResponse response = configurer.createPrincipal(principalName);
        return new PolarisSerialiser().fromJson(response.getEntity().getContent(), PrincipalWithCredentials.class);
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

}
