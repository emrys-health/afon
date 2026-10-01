package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.FileUserProvider;
import health.emrys.afon.security.IdentityProvider;
import health.emrys.afon.security.keycloak.KeyCloakProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Allows the realm/client/user to be provisioned independently of the containers, e.g. against an
 * already-running Identity provider.
 */
public class IdentityProviderSmoke {

    private static final Logger logger = LoggerFactory.getLogger(IdentityProviderSmoke.class);

    public void smokeTest(IdentityProvider provider, String username) {
        logger.info("Registering user {} from UserProvider", username);
        AfonUser user = new FileUserProvider().getUser(username);
        provider.registerPolarisUser(user);
    }

    private void smokeTest(IdentityProvider provider, AfonPropertyProvider properties) {
        String jupyterDomain = properties.getValue(AfonProperty.AWS_JUPYTER_DOMAIN_NAME);
        logger.info("Registering browser client for jupyter domain {}", jupyterDomain);
        provider.registerBrowserClient(
                properties.getValue(AfonProperty.OAUTH2_CLIENT_ID),
                properties.getValue(AfonProperty.OAUTH2_CLIENT_SECRET),
                KeyCloakProvider.callbackURIs(jupyterDomain)
        );
        provider.configureMfa();
    }

    public static void main(String[] args) {
        var app = new IdentityProviderSmoke();
        var properties = new FilePropertyProvider();
        IdentityProvider provider = new KeyCloakProvider(properties);
        if (args.length == 1) {
            String username = args[0];
            app.smokeTest(provider, username);
        } else {
            app.smokeTest(provider, properties);
        }
    }

}
