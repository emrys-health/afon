package health.emrys.afon.ops;

import health.emrys.afon.config.*;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.config.PolarisEndpoint;
import health.emrys.afon.polaris.config.PolarisUserManager;
import health.emrys.afon.security.IdentityProvider;
import health.emrys.afon.security.keycloak.KeyCloakProvider;
import org.apache.hc.core5.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.security.keycloak.KeyCloakConfigurer.KEYCLOAK_REALM;

public class ConfigureUsersMain {

    private static final Logger logger = LoggerFactory.getLogger(ConfigureUsersMain.class);

    private final AfonUserProvider userProvider;
    private final AfonPropertyProvider propertyProvider;
    private final IdentityProvider identityProvider;
    private final PolarisUserManager polarisUsers;

    public ConfigureUsersMain(AfonUserProvider userProvider, AfonPropertyProvider propertyProvider) {
        this.userProvider     = userProvider;
        this.propertyProvider = propertyProvider;
        this.identityProvider = new KeyCloakProvider(propertyProvider);
        this.polarisUsers     = new PolarisUserManager(propertyProvider);
    }

    /**
     * Provisions the realm, the {@link AfonProperty#POLARIS_ID} bootstrap client, and each
     * configured user's account/confidential client. Independent of any Jupyter domain, so this
     * runs regardless of whether browser (Jupyter) access is configured for the cloud - headless
     * clients (Spark, Trino) only need this.
     */
    public List<HttpResponse> configureOidcUsers() throws IOException {
        logger.info("Configuring OIDC users");
        List<HttpResponse> responses = new ArrayList<>();
        String adminToken = identityProvider.adminToken();
        responses.add(identityProvider.createRealm(adminToken, KEYCLOAK_REALM));
        // POLARIS_ID/POLARIS_SECRET (e.g. "root") is not one of userProvider's human users - it's the
        // service client that catalog creation and Spark authenticate as via client_credentials once
        // Polaris defers OAuth to KeyCloak (KeyCloakPolarisRestClient).
        // It doesn't exist on a fresh KeyCloak database.
        responses.add(identityProvider.createConfidentialClient(
                adminToken, KEYCLOAK_REALM,
                propertyProvider.getValue(POLARIS_ID), propertyProvider.getValue(POLARIS_SECRET)));
        for (AfonUser user : userProvider.getUsers()) {
            responses.add(identityProvider.createUser(adminToken, KEYCLOAK_REALM, user));
            responses.add(identityProvider.createConfidentialClient(adminToken, KEYCLOAK_REALM, user.username(), user.password()));
        }
        return responses;
    }

    /**
     * Registers the browser client used for interactive (Jupyter) logins, and configures MFA on the
     * realm's browser flow. Requires {@link #configureOidcUsers()} to have already created the realm
     * and the users who must be able to pass through the MFA flow.
     */
    public List<HttpResponse> configureJupyterClient(String jupyterDomain) throws IOException {
        logger.info("Configuring browser client for jupyter domain {}", jupyterDomain);
        List<HttpResponse> responses = new ArrayList<>();
        responses.addAll(identityProvider.registerBrowserClient(
                propertyProvider.getValue(OAUTH2_CLIENT_ID),
                propertyProvider.getValue(OAUTH2_CLIENT_SECRET),
                KeyCloakProvider.callbackURIs(jupyterDomain)));
        // configure MFA so that the realm is only bound to its MFA flows,
        // once the users who must pass through them exist
        responses.addAll(identityProvider.configureMfa());
        return responses;
    }

    public List<HttpResponse> configurePolarisUsers(String catalogName) throws IncorrectConfigException, IOException {
        logger.info("Configuring Polaris users on catalog {}", catalogName);
        return polarisUsers.configureUsers(catalogName, userProvider);
    }

    /**
     * With one argument (cloud), configures OIDC users and the Jupyter browser client if the cloud
     * has a Jupyter domain - cloud-scoped, meant to run once per cloud, and before any catalog
     * exists: this is what creates the KeyCloak realm and each user's confidential client (e.g.
     * "root"), which catalog creation itself authenticates as once Polaris defers OAuth to KeyCloak.
     * With two arguments (cloud, catalog-name), configures Polaris users for that single catalog -
     * catalog-scoped, meant to run once per catalog, e.g. to pick up a catalog created after the
     * cloud was last configured.
     */
    public static void main(String[] args) {

        if (args.length != 1 && args.length != 2) {
            System.out.printf("%s requires arguments: cloud", ConfigureUsersMain.class.getSimpleName());
            System.out.printf("%s requires arguments: cloud catalog-name", ConfigureUsersMain.class.getSimpleName());
            throw new IllegalArgumentException();
        }
        String cloudName   = args[0];
        String catalogName = args.length == 2 ? args[1] : null;

        AfonCloud cloud = AfonCloud.valueOf(cloudName.toUpperCase());
        var    userProvider     = new FileUserProvider();
        var    propertyProvider = new OpsPropertyProvider();
        propertyProvider.setValue(POLARIS_ENDPOINT, new PolarisEndpoint(cloud, propertyProvider).getEndpoint());
        var app = new ConfigureUsersMain(userProvider, propertyProvider);

        if (catalogName == null) {
            try {
                app.configureOidcUsers();
                Thread.sleep(1000L); // yuck! But KeyCloak appears to asynchronously register users
            } catch (Exception e) {
                logger.error("Failed to configure OIDC users {}: {}",
                        userProvider.getUserNames(), e.getMessage(), e);
                throw new RuntimeException(e);
            }

            String jupyterDomain = jupyterDomain(cloud, propertyProvider);
            if (jupyterDomain != null) {
                try {
                    app.configureJupyterClient(jupyterDomain);
                } catch (Exception e) {
                    logger.error("Failed to configure Jupyter client for jupyter domain {}: {}",
                            jupyterDomain, e.getMessage(), e);
                    throw new RuntimeException(e);
                }
            }
        } else {
            try {
                app.configurePolarisUsers(catalogName);
            } catch (Exception e) {
                logger.error("Failed to configure Polaris users {}: {}",
                        userProvider.getUserNames(), e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }
    }

    private static String jupyterDomain(AfonCloud cloud, AfonPropertyProvider propertyProvider) {
        String jupyterDomain;
        switch (cloud) {
            case AWS:
                jupyterDomain = propertyProvider.getValue(AWS_JUPYTER_DOMAIN_NAME);
                break;
            case AZURE:
                jupyterDomain = propertyProvider.getValue(AZURE_JUPYTER_DOMAIN_NAME, null);
                break;
            case GCP:
                jupyterDomain = propertyProvider.getValue(GCP_JUPYTER_DOMAIN_NAME, null);
                break;
            default:
                throw new IllegalArgumentException("Unknown cloud: " + cloud);
        }
        return jupyterDomain;
    }


}
