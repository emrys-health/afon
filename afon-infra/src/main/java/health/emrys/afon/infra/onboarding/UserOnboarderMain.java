package health.emrys.afon.infra.onboarding;

import health.emrys.afon.cdk.Onboarding;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.comms.ConfigureUserMain;
import health.emrys.afon.security.keycloak.KeyCloakAdmin;
import health.emrys.afon.security.keycloak.OnboardingConfig;
import health.emrys.afon.security.keycloak.OnboardingConfig.EmrysUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Properties;
import java.util.stream.Collectors;

import static health.emrys.afon.config.AfonProperties.POLARIS_ENDPOINT;
import static health.emrys.afon.polaris.comms.ConfigureUserMain.ROLE_NAME;
import static health.emrys.afon.security.keycloak.KeyCloakConfig.KEYCLOAK_REALM;

public class UserOnboarderMain {

    private static final Logger log = LoggerFactory.getLogger(UserOnboarderMain.class);

    public static void main(String[] args) throws Exception {
        Properties properties = new AfonPropertiesLoader().getProperties();
        properties.put(POLARIS_ENDPOINT.getPropertyName(), args[0]);
        UserOnboarderMain app           = new UserOnboarderMain(new Onboarding(), properties);
        String            catalog       = args[1];
        String            jupyterDomain = args[2];
        try {
            app.configureKeyCloak(jupyterDomain);
            app.onboardAll(catalog); // just for a smoke test
        } catch (Exception e) {
            log.error(
                "Could not onboard all users:\n{}\nError = {}",
                app.onboardingConfig.getUsers().stream().map(x -> x.userName()).collect(Collectors.joining("\n")),
                e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private final OnboardingConfig onboardingConfig;
    private final Properties       properties;
    private final KeyCloakAdmin    kcAdmin;

    public UserOnboarderMain(OnboardingConfig onboardingConfig, Properties properties) {
        this.onboardingConfig = onboardingConfig;
        this.properties       = properties;
        kcAdmin               = new KeyCloakAdmin(properties);
    }

    public void onboardAll(String warehouse) throws IncorrectConfigException, IOException {
        log.info("CONFIGURING POLARIS");
        ConfigureUserMain polarisConfiguration = new ConfigureUserMain(properties);
        polarisConfiguration.configurePrivilegesFor(warehouse, ROLE_NAME);
        for (EmrysUser user : onboardingConfig.getUsers()) {
            polarisConfiguration.configureUser(user.userName(), ROLE_NAME, warehouse);
        }
    }

    private void configureKeyCloak(String jupyterDomain) throws IOException {
        log.info("CONFIGURING KEYCLOAK");
        kcAdmin.registerBrowser(properties, jupyterDomain);
        String adminToken = kcAdmin.adminToken();
        kcAdmin.createRealm(adminToken, KEYCLOAK_REALM);
        for (EmrysUser user : onboardingConfig.getUsers()) {
            kcAdmin.createUser(adminToken, KEYCLOAK_REALM, user.userName(), user.password(), user.email());
            kcAdmin.createConfidentialClient(adminToken, KEYCLOAK_REALM, user.userName(), user.password());
        }
    }

}
