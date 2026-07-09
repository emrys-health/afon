package health.emrys.afon.security.keycloak;

import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.config.ConfigUtils.protocolFor;
import static health.emrys.afon.security.keycloak.KeyCloakConfig.KEYCLOAK_REALM;
import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Provisions, via the KeyCloak admin REST API, the realm, client and user/password that Polaris
 * trusts. After this has run, a client can obtain a token from KeyCloak using
 *
 * <p>Uses no extra dependencies beyond the Apache HttpClient5 + Jackson already on the classpath.
 */
public class KeyCloakAdmin {

    private static final Logger logger = getLogger(KeyCloakAdmin.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String adminUser;
    private final String adminPassword;

    public KeyCloakAdmin(Properties properties) {
        this(properties, KC_HOSTNAME.getValueFrom(properties));
    }

    public KeyCloakAdmin(Properties properties, String kcHostname) {
        this.baseUrl       = KeyCloakConfig.urlFor(kcHostname, protocolFor(kcHostname));
        this.adminUser     = KC_BOOTSTRAP_ADMIN_USERNAME.getValueFrom(properties);
        this.adminPassword = KC_BOOTSTRAP_ADMIN_PASSWORD.getValueFrom(properties);
        logger.info("KeyCloak Admin URL: {}", this.baseUrl);
    }

    /**
     * Ensures the Polaris realm, client and user all exist. Idempotent: anything that already exists
     * is left untouched.
     */
    public void registerPolarisUser(String user, String userPassword) {
        registerPolarisUser(user, userPassword, null);
    }

    /**
     * As {@link #registerPolarisUser(String, String)} but also sets the user's (verified) email.
     * A verified email is required for logins that go through oauth2-proxy, which rejects id_tokens
     * whose email is unverified. Pass {@code null}/blank to leave the email unset.
     */
    public void registerPolarisUser(String user, String userPassword, String email) {
        try {
            String adminToken = adminToken();
            createRealm(adminToken, KEYCLOAK_REALM);
            createConfidentialClient(adminToken, KEYCLOAK_REALM, user, userPassword);
            createUser(adminToken, KEYCLOAK_REALM, user, userPassword, email);
            logger.info(
                "KeyCloak realm '{}' provisioned with client/user '{}' (email '{}')",
                KEYCLOAK_REALM, user, email);
        } catch (IOException e) {
            throw new RuntimeException("Failed to provision KeyCloak for Polaris", e);
        }
    }

    /**
     * Authenticates against the master realm as the bootstrap admin and returns an access token for
     * the admin REST API.
     */
    public String adminToken() throws IOException {
        String uri = format("%s/realms/master/protocol/openid-connect/token", baseUrl);
        String body = form(Map.of(
            "grant_type", "password",
            "client_id", "admin-cli",
            "username", adminUser,
            "password", adminPassword
        ));
        HttpPost post = new HttpPost(uri);
        post.setHeader("Content-Type", "application/x-www-form-urlencoded");
        post.setEntity(new StringEntity(body));
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            logger.info("Calling {}", uri);
            return client.execute(
                post, response -> {
                    logger.info("Calling {} resulted in HTTP code {}", uri, response.getCode());
                    if (response.getCode() != HttpStatus.SC_OK) {
                        throw new IOException("Could not obtain KeyCloak admin token: HTTP " + response.getCode());
                    }
                    Map<?, ?> json = MAPPER.readValue(response.getEntity().getContent(), Map.class);
                    return (String) json.get("access_token");
                });
        }
    }

    public void createRealm(String adminToken, String realm) throws IOException {
        String json = MAPPER.writeValueAsString(Map.of(
            "realm", realm,
            "enabled", true
        ));
        postJson(adminToken, format("%s/admin/realms", baseUrl), json, "realm " + realm);
    }

    /**
     * Creates a confidential client with direct access grants enabled so that the user's
     * username/password can be exchanged for a token.
     */
    public void createConfidentialClient(
        String adminToken,
        String realm,
        String clientId,
        String clientSecret) throws IOException {
        String json = MAPPER.writeValueAsString(Map.of(
            "clientId", clientId,
            "secret", clientSecret,
            "enabled", true,
            "protocol", "openid-connect",
            "publicClient", false,
            "serviceAccountsEnabled", true,
            "directAccessGrantsEnabled", true,
            "standardFlowEnabled", false
        ));
        postJson(adminToken, format("%s/admin/realms/%s/clients", baseUrl, realm), json, "client " + clientId);
    }

    /**
     * Ensures the realm exists and (idempotently) creates the confidential, browser-flow client that
     * oauth2-proxy and JupyterHub use to log users in. Unlike {@link #createConfidentialClient} (a
     * direct-grant client for Polaris), this client has the authorization-code flow enabled and
     * registered redirect URIs, so the browser redirect from oauth2-proxy resolves - otherwise
     * KeyCloak returns "Client not found".
     *
     * @param redirectUris the exact callback URLs, e.g.
     *                     {@code https://jupyter.example/oauth2/callback} (oauth2-proxy) and
     *                     {@code https://jupyter.example/jupyter/hub/oauth_callback} (JupyterHub).
     */
    public void registerBrowserClient(String clientId, String clientSecret, List<String> redirectUris) {
        try {
            String adminToken = adminToken();
            createRealm(adminToken, KEYCLOAK_REALM);
            createBrowserClient(adminToken, KEYCLOAK_REALM, clientId, clientSecret, redirectUris);
            logger.info(
                "KeyCloak realm '{}' provisioned with browser client '{}' (redirects {})",
                KEYCLOAK_REALM, clientId, redirectUris);
        } catch (IOException e) {
            throw new RuntimeException("Failed to provision KeyCloak browser client", e);
        }
    }

    /**
     * Creates a confidential client for the OAuth2 authorization-code (browser) flow: standard flow
     * on, direct grants and service accounts off, with the given redirect URIs whitelisted.
     */
    public void createBrowserClient(
        String adminToken,
        String realm,
        String clientId,
        String clientSecret,
        List<String> redirectUris) throws IOException {
        String json = MAPPER.writeValueAsString(Map.ofEntries(
            Map.entry("clientId", clientId),
            Map.entry("secret", clientSecret),
            Map.entry("enabled", true),
            Map.entry("protocol", "openid-connect"),
            Map.entry("publicClient", false),
            Map.entry("standardFlowEnabled", true),
            Map.entry("directAccessGrantsEnabled", false),
            Map.entry("serviceAccountsEnabled", false),
            Map.entry("redirectUris", redirectUris),
            // "+" tells KeyCloak to derive allowed CORS web origins from the redirect URIs.
            Map.entry("webOrigins", List.of("+"))
        ));
        postJson(adminToken, format("%s/admin/realms/%s/clients", baseUrl, realm), json, "browser client " + clientId);
    }

    public void createUser(
        String adminToken,
        String realm,
        String username,
        String password,
        String email) throws IOException {
        Map<String, Object> user = new HashMap<>();
        user.put("username", username);
        user.put("enabled", true);
        // emailVerified is always true because oauth2-proxy rejects logins whose id_token email is
        // unverified; there is no SMTP verification flow behind this Keycloak.
        user.put("emailVerified", true);
        if (email != null && !email.isBlank()) {
            user.put("email", email);
        }
        user.put(
            "credentials", List.of(Map.of(
                "type", "password",
                "value", password,
                "temporary", false
            )));
        String json = MAPPER.writeValueAsString(user);
        int code = postJson(adminToken, format("%s/admin/realms/%s/users", baseUrl, realm), json, "user " + username);
        // A 409 on POST /users is ambiguous: it means the username already exists (genuinely
        // idempotent - fine) OR that some other unique field collides, typically a duplicate email
        // when the realm has duplicateEmailsAllowed=false. In the latter case the user is NOT created,
        // so silently treating the 409 as success (as postJson does) leaves logins failing with
        // user_not_found. Confirm the user really exists; if not, fail loudly with the likely cause.
        if (code == HttpStatus.SC_CONFLICT && !userExists(adminToken, realm, username)) {
            throw new IOException(format(
                "Could not create KeyCloak user '%s' in realm '%s': HTTP 409 but no user with that "
                + "username exists. The email '%s' is most likely already held by another user "
                + "(realm duplicateEmailsAllowed=false).", username, realm, email));
        }
    }

    /**
     * @return true if a user with exactly this username exists in the realm.
     */
    private boolean userExists(String adminToken, String realm, String username) throws IOException {
        String uri = format(
            "%s/admin/realms/%s/users?exact=true&username=%s",
            baseUrl, realm, URLEncoder.encode(username, StandardCharsets.UTF_8));
        HttpGet get = new HttpGet(uri);
        get.setHeader("Authorization", "Bearer " + adminToken);
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            return client.execute(get, response -> {
                if (response.getCode() != HttpStatus.SC_OK) {
                    return false;
                }
                List<?> users = MAPPER.readValue(response.getEntity().getContent(), List.class);
                return !users.isEmpty();
            });
        }
    }

    private int postJson(String adminToken, String uri, String json, String what) throws IOException {
        logger.info("Calling {} with: \n{}", uri, json);
        HttpPost post = new HttpPost(uri);
        post.setHeader("Authorization", "Bearer " + adminToken);
        post.setHeader("Content-Type", "application/json");
        post.setEntity(new StringEntity(json, StandardCharsets.UTF_8));
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            return client.execute(
                post, response -> {
                    int code = response.getCode();
                    logger.info("Calling {} resulted in HTTP code {}", uri, code);
                    if (code == HttpStatus.SC_CREATED) {
                        logger.info("Created KeyCloak {}", what);
                    } else if (code == HttpStatus.SC_CONFLICT) {
                        logger.info("KeyCloak {} already exists - leaving it untouched", what);
                    } else {
                        throw new IOException(format("Could not create KeyCloak %s: HTTP %d", what, code));
                    }
                    return code;
                });
        }
    }

    /**
     * Convenience check used by callers to wait until KeyCloak is serving requests.
     */
    public boolean isReady() {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(format("%s/realms/master", baseUrl));
            return client.execute(get, response -> response.getCode() == HttpStatus.SC_OK);
        } catch (IOException e) {
            return false;
        }
    }

    private static String form(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }

    /**
     * oauth2-proxy's callback path (see JupyterMfaConfig's --redirect-url).
     */
    public static final String OAUTH2_PROXY_CALLBACK = "/oauth2/callback";
    /**
     * JupyterHub's OAuthenticator callback path (HUB_BASE_URL + "hub/oauth_callback").
     */
    public static final String JUPYTERHUB_CALLBACK   = "/jupyter/hub/oauth_callback";

    /**
     * Allows the realm/client/user to be provisioned independently of the containers, e.g. against an
     * already-running KeyCloak.
     */
    public static void main(String[] args) {
        Properties    properties = new AfonPropertiesLoader().getProperties();
        KeyCloakAdmin admin      = new KeyCloakAdmin(properties);
        if (args.length == 3) {
            String userID   = args[0];
            String password = args[1];
            String email    = args[2];
            logger.info("user ID = {}, password = {}, email = {}", userID, password, email);
            admin.registerPolarisUser(
                userID,
                password,
                email
            );
        } else {
            admin.registerPolarisUser(
                AfonProperties.USER_POLARIS_ID.getValueFrom(properties),
                AfonProperties.USER_POLARIS_SECRET.getValueFrom(properties),
                AfonProperties.USER_POLARIS_EMAIL.getValueFrom(properties, ""));

            // Provision the browser-flow client that oauth2-proxy and JupyterHub share, otherwise clicking
            // "sign in" on the OAuth splash yields KeyCloak's "Client not found".
            String jupyterDomain = AfonProperties.AWS_JUPYTER_DOMAIN.getValueFrom(properties);
            admin.registerBrowserClient(
                AfonProperties.OAUTH2_CLIENT_ID.getValueFrom(properties),
                AfonProperties.OAUTH2_CLIENT_SECRET.getValueFrom(properties),
                List.of(
                    "https://" + jupyterDomain + OAUTH2_PROXY_CALLBACK,
                    "https://" + jupyterDomain + JUPYTERHUB_CALLBACK));
        }
    }
}