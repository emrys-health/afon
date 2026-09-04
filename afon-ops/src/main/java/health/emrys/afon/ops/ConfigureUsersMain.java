package health.emrys.afon.ops;

import health.emrys.afon.config.*;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.config.PolarisUserManager;
import health.emrys.afon.security.OAuthProvider;
import health.emrys.afon.security.keycloak.KeyCloakProvider;
import org.apache.hc.core5.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;
import static health.emrys.afon.security.keycloak.KeyCloakConfigurer.KEYCLOAK_REALM;

public class ConfigureUsersMain {

    private static final Logger logger = LoggerFactory.getLogger(ConfigureUsersMain.class);

    private final AfonUserProvider          userProvider;
    private final AfonPropertyProvider      propertyProvider;
    private final OAuthProvider             oauthProvider;
    private final PolarisUserManager        polarisUsers;

    public ConfigureUsersMain(AfonUserProvider userProvider, AfonPropertyProvider propertyProvider) {
        this.userProvider     = userProvider;
        this.propertyProvider = propertyProvider;
        this.oauthProvider    = new KeyCloakProvider(propertyProvider);
        this.polarisUsers     = new PolarisUserManager(propertyProvider);
    }

    public List<HttpResponse> configureOAuthUsers(String jupyterDomain) throws IOException {
        logger.info("Configuring OAuth users for jupyter domain {}", jupyterDomain);
        List<HttpResponse> responses = new ArrayList<>();
        responses.addAll(oauthProvider.registerBrowserClient(propertyProvider, jupyterDomain));
        String adminToken = oauthProvider.adminToken();
        responses.add(oauthProvider.createRealm(adminToken, KEYCLOAK_REALM));
        for (AfonUser user : userProvider.getUsers()) {
            responses.add(oauthProvider.createUser(adminToken, KEYCLOAK_REALM, user));
            responses.add(oauthProvider.createConfidentialClient(adminToken, KEYCLOAK_REALM, user.username(), user.password()));
        }
        // Last, so that the realm is only bound to its MFA flows once the users who must pass through
        // them exist.
        responses.addAll(oauthProvider.configureMfa());
        return responses;
    }

    public List<HttpResponse> configurePolarisUsers(String catalogName) throws IncorrectConfigException, IOException {
        logger.info("Configuring Polaris users on catalog {}", catalogName);
        return polarisUsers.configureUsers(catalogName, userProvider);
    }

    public List<HttpResponse> configureUsers(String jupyterDomain, String catalogName) throws IOException, IncorrectConfigException, InterruptedException {
        List<HttpResponse> responses = new ArrayList<>();
        responses.addAll(configureOAuthUsers(jupyterDomain));
        Thread.sleep(1000L); // yuck! But KeyClaok appears to asynchronously register users
        responses.addAll(configurePolarisUsers(catalogName));
        return responses;
    }

    public static void main(String[] args) {

        String polarisDomain;
        String catalogName;
        String jupyterDomain = null;

        if (args.length == 2) {
            polarisDomain  = args[0];
            catalogName = args[1];
        } else if (args.length == 3) {
            polarisDomain  = args[0];
            catalogName = args[1];
            jupyterDomain = args[2];
        } else {
            System.out.printf("%s requires arguments: polaris-domain catalog-name", ConfigureUsersMain.class.getSimpleName());
            System.out.printf("%s requires arguments: polaris-domain catalog-name jupyter-domain", ConfigureUsersMain.class.getSimpleName());
            throw new IllegalArgumentException();
        }
        var    userProvider     = new FileUserProvider();
        var    propertyProvider = new FilePropertyProvider();
        propertyProvider.setValue(POLARIS_ENDPOINT, polarisDomain);
        var app = new ConfigureUsersMain(userProvider, propertyProvider);

        if (jupyterDomain == null) {
            try {
                app.configurePolarisUsers(catalogName);
            } catch (Exception e) {
                logger.error("Failed to configure Polaris users {}: {}",
                        userProvider.getUserNames(), e.getMessage(), e);
                throw new RuntimeException(e);
            }
        } else {
            try {
                app.configureUsers(jupyterDomain, catalogName);
            } catch (Exception e) {
                logger.error("Failed to configure Keycloak users {}: {}",
                        userProvider.getUserNames(), e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }
    }


}
