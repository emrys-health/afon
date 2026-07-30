package health.emrys.afon.ops;

import health.emrys.afon.cdk.Onboarding;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.security.keycloak.KeyCloakAdmin;
import health.emrys.afon.security.keycloak.OnboardingConfig;
import health.emrys.afon.security.keycloak.OnboardingConfig.EmrysUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Properties;
import java.util.stream.Collectors;

import static health.emrys.afon.config.AfonProperties.POLARIS_ENDPOINT;
import static health.emrys.afon.security.keycloak.KeyCloakConfig.KEYCLOAK_REALM;

public class OnboardAllUsersMain {

    private static final Logger logger = LoggerFactory.getLogger(OnboardAllUsersMain.class);

    private final OnboardingConfig onboardingConfig;
    private final Properties       properties;
    private final KeyCloakAdmin    kcAdmin;

    public OnboardAllUsersMain(OnboardingConfig onboardingConfig, Properties properties) {
        this.onboardingConfig = onboardingConfig;
        this.properties       = properties;
        kcAdmin               = new KeyCloakAdmin(properties);
    }

    public void onboardAll(String warehouse) throws IncorrectConfigException, IOException {
        logger.info("CONFIGURING POLARIS");
        ConfigurePolarisUserMain polarisConfiguration = new ConfigurePolarisUserMain(properties);
        polarisConfiguration.configurePrivilegesFor(warehouse);
        polarisConfiguration.configureAll(warehouse, onboardingConfig);
    }

    public void configureKeyCloak(String jupyterDomain) throws IOException {
        logger.info("CONFIGURING KEYCLOAK");
        kcAdmin.registerBrowser(properties, jupyterDomain);
        String adminToken = kcAdmin.adminToken();
        kcAdmin.createRealm(adminToken, KEYCLOAK_REALM);
        for (EmrysUser user : onboardingConfig.getUsers()) {
            kcAdmin.createUser(adminToken, KEYCLOAK_REALM, user);
            kcAdmin.createConfidentialClient(adminToken, KEYCLOAK_REALM, user.userName(), user.password());
        }
    }

    public static void main(String[] args) {
        Properties properties = new AfonPropertiesLoader().getProperties();
        properties.put(POLARIS_ENDPOINT.getName(), args[0]);
        OnboardAllUsersMain app           = new OnboardAllUsersMain(new Onboarding(), properties);
        String            catalog       = args[1];
        String            jupyterDomain = args[2];
        try {
            app.configureKeyCloak(jupyterDomain);
            app.onboardAll(catalog); // just for a smoke test
        } catch (Exception e) {
            logger.error(
                    "Failed to onboard all users:\n{}\nError = {}",
                    app.onboardingConfig.getUsers().stream().map(x -> x.userName()).collect(Collectors.joining("\n")),
                    e.getMessage());
            throw new RuntimeException(e);
        }
    }


}
