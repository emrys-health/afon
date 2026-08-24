package health.emrys.afon.ops;

import health.emrys.afon.config.*;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.security.OAuthProvider;
import health.emrys.afon.security.keycloak.KeyCloakProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;
import static health.emrys.afon.security.keycloak.KeyCloakConfigurer.KEYCLOAK_REALM;

public class ConfigureUsersMain {

    private static final Logger logger = LoggerFactory.getLogger(ConfigureUsersMain.class);

    private final AfonUserProvider userProvider;
    private final AfonPropertyProvider propertyProvider;
    private final OAuthProvider keyCloakProvider;
    private final ConfigurePolarisUsersMain polarisUsers;

    public ConfigureUsersMain(AfonUserProvider userProvider, AfonPropertyProvider propertyProvider) {
        this.userProvider = userProvider;
        this.propertyProvider = propertyProvider;
        this.keyCloakProvider = new KeyCloakProvider(propertyProvider);
        this.polarisUsers = new ConfigurePolarisUsersMain(propertyProvider);
    }

    public void configureKeyCloak(String jupyterDomain) throws IOException {
        logger.info("Configuring KeyCloak users for jupyter domain {}", jupyterDomain);
        keyCloakProvider.registerBrowserClient(propertyProvider, jupyterDomain);
        String adminToken = keyCloakProvider.adminToken();
        keyCloakProvider.createRealm(adminToken, KEYCLOAK_REALM);
        for (AfonUser user : userProvider.getUsers()) {
            keyCloakProvider.createUser(adminToken, KEYCLOAK_REALM, user);
            keyCloakProvider.createConfidentialClient(adminToken, KEYCLOAK_REALM, user.username(), user.password());
        }
        // Last, so that the realm is only bound to its MFA flows once the users who must pass through
        // them exist.
        keyCloakProvider.configureMfa();
    }

    public void configurePolaris(String catalogName) throws IncorrectConfigException, IOException {
        logger.info("Configuring Polaris catalog {} and users", catalogName);
        polarisUsers.configureCatalog(catalogName);
        polarisUsers.configureUsers(catalogName, userProvider);
    }

    public void configureUsers(String jupyterDomain, String catalogName) throws IOException, IncorrectConfigException {
        configureKeyCloak(jupyterDomain);
        configurePolaris(catalogName);
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            logger.warn("{} requires following args: polaris-domain catalog-name jupyter-domain", ConfigureUsersMain.class.getName());
            return;
        }
        String polarisDomain = args[0];
        String catalogName = args[1];
        String jupyterDomain = args[2];
        var userProvider = new FileUserProvider();
        var propertyProvider = new FilePropertyProvider();
        propertyProvider.setValue(POLARIS_ENDPOINT, polarisDomain);
        var app = new ConfigureUsersMain(userProvider, propertyProvider);
        try {
            app.configureUsers(jupyterDomain, catalogName);
        } catch (Exception e) {
            logger.error("Failed to configure KeyCloak users {}: {}",
                    app.userProvider.getUserNames(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }


}
