package health.emrys.afon.ops.smoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static health.emrys.afon.config.AfonProperty.USER_ID;
import static health.emrys.afon.security.keycloak.KeyCloakConfigurer.KEYCLOAK_REALM;
import static java.lang.String.format;

/**
 * Smoke test that a KeyCloak <em>user</em>, rather than an OAuth client, can get an OIDC token from the
 * {@code polaris} realm of the TRE cloud, with its username and password.
 * <p>
 * The token is got with the direct-access (password) grant through KeyCloak's own public {@code admin-cli} client, which
 * every realm has, so no client of Afon's, nor its secret, is used: only the user's credentials.  The realm's direct-grant
 * flow has its OTP step taken out (see {@link KeyCloakProvider#DIRECT_GRANT_NO_OTP_FLOW}), so no second factor is asked.
 * <p>
 * The user is the one named as the first argument, else {@code USER_ID}.  Its password is that of users.csv, if it is there,
 * else the human running the test is asked for it on the console.
 * <p>
 * Checks that the token is for that user, is issued by the realm and has not expired, and that a wrong password is refused.
 */
public class OidcUserTokenSmokeMain {

    private static final Logger       logger = LoggerFactory.getLogger(OidcUserTokenSmokeMain.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
     * @return the errors, if any, of getting a token for the user
     */
    public List<String> check(String username) throws IOException {
        List<String> errors   = new ArrayList<>();
        String       password = passwordFor(username);
        logger.info("Getting an OIDC token for user '{}' from realm '{}' of {} at {}",
                    username, KEYCLOAK_REALM, cloud, properties.getOidcDomainName(cloud));
        String token;
        try {
            token = keyCloak.getTokenFor(KEYCLOAK_REALM, username, password);
        } catch (IllegalStateException e) {
            errors.add(format("User '%s' could not get a token: %s", username, e.getMessage()));
            return errors;
        }
        errors.addAll(checkClaims(claimsOf(token), username, Instant.now()));
        errors.addAll(checkWrongPasswordRefused(username, password));
        if (errors.isEmpty()) {
            logger.info("User '{}' got a valid OIDC token", username);
        }
        return errors;
    }

    private List<String> checkWrongPasswordRefused(String username, String password) throws IOException {
        try {
            keyCloak.getTokenFor(KEYCLOAK_REALM, username, password + "-wrong");
            return List.of(format("User '%s' got a token with a wrong password", username));
        } catch (IllegalStateException e) {
            logger.info("A wrong password for '{}' was correctly refused: {}", username, e.getMessage());
            return List.of();
        }
    }

    /**
     * @return the errors of the claims not being those of a live token of the user, issued by the realm
     */
    static List<String> checkClaims(JsonNode claims, String username, Instant now) {
        List<String> errors   = new ArrayList<>();
        String       subject  = claims.path("preferred_username").asText("");
        if (!username.equalsIgnoreCase(subject)) {
            errors.add(format("Token is for '%s', not '%s'", subject, username));
        }
        String issuer = claims.path("iss").asText("");
        if (!issuer.endsWith("/realms/" + KEYCLOAK_REALM)) {
            errors.add(format("Token was issued by '%s', not realm '%s'", issuer, KEYCLOAK_REALM));
        }
        long expiry = claims.path("exp").asLong(0);
        if (expiry <= now.getEpochSecond()) {
            errors.add(format("Token expired at %s, before %s", Instant.ofEpochSecond(expiry), now));
        }
        return errors;
    }

    /**
     * @return the payload of the JWT, which is not verified, as KeyCloak just issued it over TLS
     */
    static JsonNode claimsOf(String jwt) throws IOException {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            throw new IOException("Token is not a JWT");
        }
        byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
        return MAPPER.readTree(new String(payload, StandardCharsets.UTF_8));
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
