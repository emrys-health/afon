package health.emrys.afon.ops;

import health.emrys.afon.config.FileUserProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.security.keycloak.KeyCloakProvider;
import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.AfonUserProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Properties;
import java.util.stream.Collectors;

import static health.emrys.afon.config.AfonProperties.POLARIS_ENDPOINT;
import static health.emrys.afon.security.keycloak.KeyCloakConfigurer.KEYCLOAK_REALM;

public class OnboardAllUsersMain {

    private static final Logger logger = LoggerFactory.getLogger(OnboardAllUsersMain.class);

    private final AfonUserProvider userProvider;
    private final Properties       properties;
    private final KeyCloakProvider keyCloakProvider;

    public OnboardAllUsersMain(AfonUserProvider userProvider, Properties properties) {
        this.userProvider = userProvider;
        this.properties = properties;
        this.keyCloakProvider = new KeyCloakProvider(properties);
    }

    public void onboardAll(String warehouse) throws IncorrectConfigException, IOException {
        logger.info("CONFIGURING POLARIS");
        ConfigurePolarisUserMain polarisConfiguration = new ConfigurePolarisUserMain(properties);
        polarisConfiguration.configurePrivilegesFor(warehouse);
        polarisConfiguration.configureAll(warehouse, userProvider);
    }

    public void configureKeyCloak(String jupyterDomain) throws IOException {
        logger.info("CONFIGURING KEYCLOAK");
        keyCloakProvider.registerBrowser(properties, jupyterDomain);
        String adminToken = keyCloakProvider.adminToken();
        keyCloakProvider.createRealm(adminToken, KEYCLOAK_REALM);
        for (AfonUser user : userProvider.getUsers()) {
            keyCloakProvider.createUser(adminToken, KEYCLOAK_REALM, user);
            keyCloakProvider.createConfidentialClient(adminToken, KEYCLOAK_REALM, user.username(), user.password());
        }
    }

    public static void main(String[] args) {
        Properties properties = new FilePropertyProvider().getProperties();
        properties.put(POLARIS_ENDPOINT.getName(), args[0]);
        OnboardAllUsersMain app         = new OnboardAllUsersMain(new FileUserProvider(), properties);
        String            catalog       = args[1];
        String            jupyterDomain = args[2];
        try {
            app.configureKeyCloak(jupyterDomain);
            app.onboardAll(catalog); // just for a smoke test
        } catch (Exception e) {
            logger.error(
                    "Failed to onboard all users:\n{}\nError = {}",
                    app.userProvider.getUsers().stream().map(x -> x.username()).collect(Collectors.joining("\n")),
                    e.getMessage());
            throw new RuntimeException(e);
        }
    }


}
