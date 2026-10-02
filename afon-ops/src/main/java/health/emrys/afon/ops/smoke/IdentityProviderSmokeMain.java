package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonProperty;
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
public class IdentityProviderSmokeMain {

    private static final Logger logger = LoggerFactory.getLogger(IdentityProviderSmokeMain.class);
    private final IdentityProvider provider;

    public IdentityProviderSmokeMain(IdentityProvider identityProvider) {
        this.provider = identityProvider;
    }

    public void registerUser(String username) {
        logger.info("Registering user {} from UserProvider", username);
        AfonUser user = new FileUserProvider().getUser(username);
        provider.registerPolarisUser(user);
    }

    private void registerBrowserClient(String jupyterDomain, String clientId, String clientSecret) {
        logger.info("Registering browser client for jupyter domain {}", jupyterDomain);
        provider.registerBrowserClient(clientId, clientSecret, KeyCloakProvider.callbackURIs(jupyterDomain));
        provider.configureMfa();
    }

    public static void main(String[] args) {
        var properties = new FilePropertyProvider();
        IdentityProvider provider = new KeyCloakProvider(properties);
        var app = new IdentityProviderSmokeMain(provider);
        int exitCode = 0;
        try {
            if (args.length == 1) {
                String username = args[0];
                app.registerUser(username);
            } else {
                String jupyterDomain = properties.getValue(AfonProperty.AWS_JUPYTER_DOMAIN_NAME);
                String clientId = properties.getValue(AfonProperty.OAUTH2_CLIENT_ID);
                String clientSecret = ***REMOVED***
                app.registerBrowserClient(jupyterDomain, clientId, clientSecret);
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            exitCode = 1;
        }
        System.exit(exitCode);
    }

}
