package health.emrys.afon.ops;

import health.emrys.afon.config.*;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.security.keycloak.KeyCloakProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;
import static health.emrys.afon.security.keycloak.KeyCloakConfigurer.KEYCLOAK_REALM;

public class ConfigureKeyCloakUsersMain {

    private static final Logger logger = LoggerFactory.getLogger(ConfigureKeyCloakUsersMain.class);

    private final AfonUserProvider userProvider;
    private final AfonPropertyProvider propertyProvider;
    private final KeyCloakProvider keyCloakProvider;
    private final ConfigurePolarisUsersMain polarisUsers;

    public ConfigureKeyCloakUsersMain(AfonUserProvider userProvider, AfonPropertyProvider propertyProvider, Properties properties) {
        this.userProvider = userProvider;
        this.propertyProvider = propertyProvider;
        this.keyCloakProvider = new KeyCloakProvider(propertyProvider);
        this.polarisUsers = new ConfigurePolarisUsersMain(properties);
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

    public static void main(String[] args) {
        if (args.length != 3) {
            logger.warn("{} requires following args: polaris-domain catalog-name jupyter-domain", ConfigureKeyCloakUsersMain.class.getName());
            return;
        }
        String polarisDomain = args[0];
        String catalogName = args[1];
        String jupyterDomain = args[2];
        var propertyProvider = new FilePropertyProvider();
        var userProvider = new FileUserProvider();
        Properties properties = propertyProvider.getProperties();
        properties.put(POLARIS_ENDPOINT.getName(), polarisDomain);
        var app = new ConfigureKeyCloakUsersMain(userProvider, propertyProvider, properties);
        try {
            app.configureKeyCloak(jupyterDomain);
            app.configurePolaris(catalogName); // just for a smoke test
        } catch (Exception e) {
            logger.error("Failed to configure KeyCloak users {}: {}",
                    app.userProvider.getUserNames(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }


}
