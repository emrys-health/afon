package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.config.FileUserProvider;
import health.emrys.afon.ops.smoke.config.SmokeConfig;
import health.emrys.afon.security.keycloak.KeyCloakProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Console;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static health.emrys.afon.config.AfonProperty.USER_ID;
import static health.emrys.afon.security.keycloak.KeyCloakConfigurer.KEYCLOAK_REALM;
import static health.emrys.afon.security.keycloak.KeyCloakProvider.ADMIN_CLI;
import static java.lang.String.format;

/**
 * Smoke test that a KeyCloak <em>user</em> cannot get an OIDC token from the {@code polaris} realm of the TRE cloud with
 * their username and password alone, ie without the second factor of MFA, which only the browser flow asks for.
 * <p>
 * The realm's direct-grant flow has its OTP step taken out (see {@link KeyCloakProvider#DIRECT_GRANT_NO_OTP_FLOW}), as a
 * headless login is guarded by a confidential client's secret instead.  So a public client, with no secret, must not take a
 * username/password: this tries the password grant through KeyCloak's own public {@value KeyCloakProvider#ADMIN_CLI}
 * client, which every realm has, and passes only if KeyCloak refuses that client direct access grants.  A refusal for any
 * other reason, eg a wrong password, fails the test, as it says nothing about the client.
 * <p>
 * The user is the one named as the first argument, else {@code USER_ID}.  Its password is that of users.csv, if it is there,
 * else the human running the test is asked for it on the console.
 */
public class OidcUserTokenSmokeMain {

    private static final Logger logger = LoggerFactory.getLogger(OidcUserTokenSmokeMain.class);

    /**
     * The OAuth error KeyCloak answers with when a client may not use the grant, here direct access grants, eg
     * {@code {"error":"unauthorized_client","error_description":"Client not allowed for direct access grants"}}.
     */
    static final String UNAUTHORIZED_CLIENT = "unauthorized_client";

    interface PasswordReader {
        String read(String username);
    }

    private final AfonCloud            cloud;
    private final AfonPropertyProvider properties;
    private final AfonUserProvider     userProvider;
    private final KeyCloakProvider     keyCloak;
    private final PasswordReader       passwordReader;

    OidcUserTokenSmokeMain(AfonCloud cloud,
                           AfonPropertyProvider properties,
                           AfonUserProvider userProvider,
                           KeyCloakProvider keyCloak,
                           PasswordReader passwordReader) {
        this.cloud          = cloud;
        this.properties     = properties;
        this.userProvider   = userProvider;
        this.keyCloak       = keyCloak;
        this.passwordReader = passwordReader;
    }

    public static OidcUserTokenSmokeMain create() {
        SmokeConfig          config     = new SmokeConfig();
        AfonCloud            cloud      = config.cloud();
        AfonPropertyProvider properties = config.getProperties();
        return new OidcUserTokenSmokeMain(
            cloud,
            properties,
            new FileUserProvider(),
            new KeyCloakProvider(properties.getOidcDomainName(cloud)),
            OidcUserTokenSmokeMain::readFromConsole);
    }

    /**
     * @return the errors, if any, of the user getting a token with only their password, or of it being refused for another
     * reason than the client taking no passwords
     */
    public List<String> check(String username) throws IOException {
        String password = passwordFor(username);
        logger.info("Trying to get an OIDC token for user '{}' with only a password, through {}, from realm '{}' of {} at {}",
                    username, ADMIN_CLI, KEYCLOAK_REALM, cloud, properties.getOidcDomainName(cloud));
        try {
            keyCloak.getTokenFor(KEYCLOAK_REALM, username, password);
            return List.of(format(
                "User '%s' got a token from realm '%s' with only a password, bypassing MFA, through public client %s: "
                + "its direct access grants should be disabled (see KeyCloakProvider#configureMfa)",
                username, KEYCLOAK_REALM, ADMIN_CLI));
        } catch (IllegalStateException e) {
            return refusalErrors(username, e.getMessage());
        }
    }

    /**
     * @param refusal the message of KeyCloak refusing the token
     * @return no error if KeyCloak refused the client the grant, else one, as the refusal was for another reason
     */
    static List<String> refusalErrors(String username, String refusal) {
        if (refusal != null && refusal.contains(UNAUTHORIZED_CLIENT)) {
            logger.info("User '{}' was correctly refused a password-only token through {}: {}", username, ADMIN_CLI, refusal);
            return List.of();
        }
        return List.of(format(
            "User '%s' was refused a token, but not because %s takes no passwords, so nothing is shown about MFA: %s",
            username, ADMIN_CLI, refusal));
    }

    private String passwordFor(String username) {
        Optional<AfonUser> user = userProvider.getUsers().stream().filter(u -> u.username().equals(username)).findFirst();
        if (user.isPresent() && user.get().password() != null && !user.get().password().isBlank()) {
            logger.info("Using the password of '{}' from users.csv", username);
            return user.get().password();
        }
        return passwordReader.read(username);
    }

    private static String readFromConsole(String username) {
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException(format(
                "User '%s' is not in users.csv and there is no console to ask for its password: run this from a terminal",
                username));
        }
        char[] password = console.readPassword("KeyCloak password for '%s': ", username);
        if (password == null || password.length == 0) {
            throw new IllegalStateException("No password entered for " + username);
        }
        return new String(password);
    }

    public static void main(String[] args) throws Exception {
        OidcUserTokenSmokeMain app      = create();
        String                 username = args.length > 0 ? args[0] : app.properties.getValue(USER_ID);
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Give the username as the first argument, or set USER_ID");
        }
        List<String> errors = app.check(username);
        if (!errors.isEmpty()) {
            String msg = String.join("\n", errors);
            logger.error(msg);
            throw new RuntimeException(msg);
        }
        logger.info("Test passed");
    }
}
