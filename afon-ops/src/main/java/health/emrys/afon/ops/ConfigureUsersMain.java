package health.emrys.afon.ops;

import health.emrys.afon.config.*;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
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
    private final KeyCloakProvider          oauthProvider;
    private final ConfigurePolarisUsersMain polarisUsers;

    public ConfigureUsersMain(AfonUserProvider userProvider, AfonPropertyProvider propertyProvider) {
        this.userProvider     = userProvider;
        this.propertyProvider = propertyProvider;
        this.oauthProvider    = new KeyCloakProvider(propertyProvider);
        this.polarisUsers     = new ConfigurePolarisUsersMain(propertyProvider);
    }

    public List<HttpResponse> configureKeyCloak(String jupyterDomain) throws IOException {
        logger.info("Configuring KeyCloak users for jupyter domain {}", jupyterDomain);
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

    public List<HttpResponse> configurePolaris(String catalogName) throws IncorrectConfigException, IOException {
        logger.info("Configuring Polaris catalog {} and users", catalogName);
        List<HttpResponse> responses = new ArrayList<>();
        responses.addAll(polarisUsers.configureCatalog(catalogName));
        responses.addAll(polarisUsers.configureUsers(catalogName, userProvider));
        return responses;
    }

    public List<HttpResponse> configureUsers(String jupyterDomain, String catalogName) throws IOException, IncorrectConfigException, InterruptedException {
        List<HttpResponse> responses = new ArrayList<>();
        responses.addAll(configureKeyCloak(jupyterDomain));
        Thread.sleep(1000L); // yuck! But KeyClaok appears to asynchronously register users
        responses.addAll(configurePolaris(catalogName));
        return responses;
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            logger.warn("{} requires following args: polaris-domain catalog-name jupyter-domain", ConfigureUsersMain.class.getName());
            return;
        }
        String polarisDomain    = args[0];
        String catalogName      = args[1];
        String jupyterDomain    = args[2];
        var    userProvider     = new FileUserProvider();
        var    propertyProvider = new FilePropertyProvider();
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
