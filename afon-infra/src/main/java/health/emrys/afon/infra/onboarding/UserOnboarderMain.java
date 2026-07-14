package health.emrys.afon.infra.onboarding;

import health.emrys.afon.cdk.Onboarding;
import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.comms.ConfigureUserMain;
import health.emrys.afon.security.keycloak.OnboardingConfig;
import health.emrys.afon.security.keycloak.OnboardingConfig.EmrysUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Properties;
import java.util.stream.Collectors;

import static health.emrys.afon.config.AfonProperties.POLARIS_ENDPOINT;
import static health.emrys.afon.polaris.comms.ConfigureUserMain.ROLE_NAME;

public class UserOnboarderMain {

    private static final Logger log = LoggerFactory.getLogger(UserOnboarderMain.class);

    public static void main(String[] args) throws Exception {
        Properties properties = new AfonPropertiesLoader().getProperties();
        if (args.length > 0) {
            properties.put(POLARIS_ENDPOINT.getPropertyName(), args[0]);
        }
        UserOnboarderMain app     = new UserOnboarderMain(new Onboarding(), properties);
        String            catalog = AfonProperties.SupportedCloudsEnum.AWS.toString();
        if (args.length > 1) {
            catalog = args[1];
        }
        try {
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

    public UserOnboarderMain(OnboardingConfig onboardingConfig, Properties properties) {
        this.onboardingConfig = onboardingConfig;
        this.properties       = properties;
    }

    private void onboardAll(String warehouse) throws IncorrectConfigException, IOException {
        ConfigureUserMain polarisConfiguration = new ConfigureUserMain(properties);
        polarisConfiguration.configurePrivilegesFor(warehouse, ROLE_NAME);
        for (EmrysUser user : onboardingConfig.getUsers()) {
            polarisConfiguration.configureUser(user.userName(), ROLE_NAME, warehouse);
        }
    }

}
