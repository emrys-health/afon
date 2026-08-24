package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.FileUserProvider;
import health.emrys.afon.security.keycloak.KeyCloakProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Allows the realm/client/user to be provisioned independently of the containers, e.g. against an
 * already-running KeyCloak.
 */
public class KeyCloakProviderSmoke {

    private static final Logger logger = LoggerFactory.getLogger(KeyCloakProviderSmoke.class);

    public void smokeTest(KeyCloakProvider provider, String username) {
        logger.info("Registering user {} from UserProvider", username);
        AfonUser user = new FileUserProvider().getUser(username);
        provider.registerPolarisUser(user);
    }

    private void smokeTest(KeyCloakProvider provider, FilePropertyProvider properties) {
        logger.info("Registering user and client from properties");
        provider.registerPolarisUserAndBrowserClient(properties);
    }

    public static void main(String[] args) {
        var app = new KeyCloakProviderSmoke();
        var properties = new FilePropertyProvider();
        KeyCloakProvider provider = new KeyCloakProvider(properties);
        if (args.length == 1) {
            String username = args[0];
            app.smokeTest(provider, username);
        } else {
            app.smokeTest(provider, properties);
        }
    }

}
