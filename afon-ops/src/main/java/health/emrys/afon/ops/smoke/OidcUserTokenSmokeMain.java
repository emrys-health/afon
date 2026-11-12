package health.emrys.afon.ops.smoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.smoke.config.SmokeConfig;
import health.emrys.afon.security.keycloak.KeyCloakProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Console;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static health.emrys.afon.config.AfonProperty.USER_ID;
import static health.emrys.afon.security.keycloak.KeyCloakConfigurer.KEYCLOAK_REALM;
import static health.emrys.afon.security.keycloak.KeyCloakProvider.ADMIN_CLI;
import static health.emrys.afon.security.keycloak.KeyCloakProvider.MFA_CLIENT;
import static java.lang.String.format;

/**
 * Smoke test that a KeyCloak <em>user</em> gets an OIDC token from the {@code polaris} realm of the TRE cloud with their
 * username, password and the one-time password of their authenticator app, ie with MFA, and that they get none without it.
 * <p>
 * The token comes through {@value KeyCloakProvider#MFA_CLIENT}, a public client, so no client secret is involved: only the
 * user's own credentials.  Its direct grant requires the one-time password (see {@link KeyCloakProvider#configureMfa}).
 * <p>
 * Checks, in this order, as KeyCloak refuses a one-time password that has been used already:
 * <ol>
 *     <li>the password alone gets no token through KeyCloak's own public {@value KeyCloakProvider#ADMIN_CLI} client,
 *     which must take no username/password;</li>
 *     <li>the password alone gets no token through {@value KeyCloakProvider#MFA_CLIENT};</li>
 *     <li>the password and one-time password get a token through {@value KeyCloakProvider#MFA_CLIENT}, which is for the
 *     user, of the realm, live and resolves to the user's Polaris principal.  That token is printed as {@code OIDC_TOKEN}.</li>
 * </ol>
 * <p>
 * The user is the one named as the first argument, else {@code USER_ID}.  The human running the test is asked for its
 * password and one-time password on the console.
 */
public class OidcUserTokenSmokeMain {

    private static final Logger       logger = LoggerFactory.getLogger(OidcUserTokenSmokeMain.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The OAuth error KeyCloak answers with when a client may not use the grant, here direct access grants, eg
     * {@code {"error":"unauthorized_client","error_description":"Client not allowed for direct access grants"}}.
     */
    static final String UNAUTHORIZED_CLIENT = "unauthorized_client";
    /**
     * The OAuth error KeyCloak answers with when the user's credentials do not authenticate them, eg a missing one-time
     * password: {@code {"error":"invalid_grant","error_description":"Invalid user credentials"}}.
     */
    static final String INVALID_GRANT       = "invalid_grant";
    /** The claim Polaris reads the principal name from, see {@code KeyCloakProvider#PRINCIPAL_CLAIM}. */
    static final String PRINCIPAL_CLAIM     = "polaris_principal";

    /** What the human running the test is asked for. */
    interface Prompter {
        String password(String username);

        String oneTimePassword(String username);
    }

    private final AfonCloud            cloud;
    private final AfonPropertyProvider properties;
    private final KeyCloakProvider     keyCloak;
    private final Prompter             prompter;
    private final PrintStream          out;

    OidcUserTokenSmokeMain(AfonCloud cloud,
                           AfonPropertyProvider properties,
                           KeyCloakProvider keyCloak,
                           Prompter prompter,
                           PrintStream out) {
        this.cloud      = cloud;
        this.properties = properties;
        this.keyCloak   = keyCloak;
        this.prompter   = prompter;
        this.out        = out;
    }

    public static OidcUserTokenSmokeMain create() {
        SmokeConfig          config     = new SmokeConfig();
        AfonCloud            cloud      = config.cloud();
        AfonPropertyProvider properties = config.getProperties();
        return new OidcUserTokenSmokeMain(
            cloud,
            properties,
            new KeyCloakProvider(properties.getOidcDomainName(cloud)),
            new ConsolePrompter(),
            System.out);
    }

    /**
     * @return the errors, if any, of the user's token, or of their getting one without their one-time password
     */
    public List<String> check(String username) throws IOException {
        logger.info("Getting an OIDC token for user '{}' from realm '{}' of {} at {}",
                    username, KEYCLOAK_REALM, cloud, properties.getOidcDomainName(cloud));
        String       password = prompter.password(username);
        List<String> errors   = new ArrayList<>();
        errors.addAll(checkPasswordAloneRefusedByAdminCli(username, password));
        errors.addAll(checkPasswordAloneRefusedByMfaClient(username, password));
        // Asked for only now, so that it is as fresh as can be when used, as an authenticator's code lasts 30 seconds
        String otp = prompter.oneTimePassword(username);
        String token;
        try {
            token = keyCloak.userToken(username, password, otp);
        } catch (IllegalStateException e) {
            errors.add(format("User '%s' got no token with their password and one-time password: %s", username, e.getMessage()));
            return errors;
        }
        errors.addAll(checkClaims(claimsOf(token), username, Instant.now()));
        if (errors.isEmpty()) {
            out.println("OIDC_TOKEN=" + token);
        }
        return errors;
    }

    private List<String> checkPasswordAloneRefusedByAdminCli(String username, String password) throws IOException {
        try {
            keyCloak.getTokenFor(KEYCLOAK_REALM, username, password);
            return List.of(format(
                "User '%s' got a token from realm '%s' with only a password, bypassing MFA, through public client %s: "
                + "its direct access grants should be disabled (see KeyCloakProvider#configureMfa)",
                username, KEYCLOAK_REALM, ADMIN_CLI));
        } catch (IllegalStateException e) {
            return refusalErrors(username, ADMIN_CLI, UNAUTHORIZED_CLIENT, e.getMessage());
        }
    }

    private List<String> checkPasswordAloneRefusedByMfaClient(String username, String password) throws IOException {
        try {
            keyCloak.userToken(username, password, "");
            return List.of(format(
                "User '%s' got a token from realm '%s' with only a password, bypassing MFA, through %s: its direct grant "
                + "should require a one-time password (see KeyCloakProvider#configureMfa)",
                username, KEYCLOAK_REALM, MFA_CLIENT));
        } catch (IllegalStateException e) {
            return refusalErrors(username, MFA_CLIENT, INVALID_GRANT, e.getMessage());
        }
    }

    /**
     * @param refusal the message of KeyCloak refusing the token
     * @return no error if KeyCloak refused it with the expected OAuth error, else one, as it was refused for another reason
     */
    static List<String> refusalErrors(String username, String client, String expectedError, String refusal) {
        if (refusal != null && refusal.contains(expectedError)) {
            logger.info("User '{}' was correctly refused a password-only token through {}: {}", username, client, refusal);
            return List.of();
        }
        return List.of(format(
            "User '%s' was refused a password-only token through %s, but not with %s, so nothing is shown about MFA: %s",
            username, client, expectedError, refusal));
    }

    /**
     * @return the errors of the claims not being those of a live token of the user, issued by the realm, that Polaris
     * resolves to the user's principal
     */
    static List<String> checkClaims(JsonNode claims, String username, Instant now) {
        List<String> errors  = new ArrayList<>();
        String       subject = claims.path("preferred_username").asText("");
        if (!username.equalsIgnoreCase(subject)) {
            errors.add(format("Token is for '%s', not '%s'", subject, username));
        }
        String principal = claims.path(PRINCIPAL_CLAIM).asText("");
        if (!username.equalsIgnoreCase(principal)) {
            errors.add(format("Token's %s is '%s', not '%s', so Polaris would not resolve it to the user",
                              PRINCIPAL_CLAIM, principal, username));
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

    private static class ConsolePrompter implements Prompter {

        @Override
        public String password(String username) {
            char[] password = console(username).readPassword("KeyCloak password for '%s': ", username);
            return required(password == null ? null : new String(password), "password", username);
        }

        @Override
        public String oneTimePassword(String username) {
            String otp = console(username).readLine("One-time password of the authenticator app of '%s': ", username);
            return required(otp == null ? null : otp.replaceAll("\\s", ""), "one-time password", username);
        }

        private static Console console(String username) {
            Console console = System.console();
            if (console == null) {
                throw new IllegalStateException(format(
                    "There is no console to ask for the credentials of '%s': run this from a terminal", username));
            }
            return console;
        }

        private static String required(String value, String what, String username) {
            if (value == null || value.isEmpty()) {
                throw new IllegalStateException(format("No %s entered for '%s'", what, username));
            }
            return value;
        }
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
