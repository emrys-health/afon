package health.emrys.afon.security.keycloak;

import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.config.*;
import health.emrys.afon.security.OAuthProvider;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.keycloak.models.utils.DefaultAuthenticationFlows;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.security.SecurityUtils.protocolFor;
import static health.emrys.afon.security.keycloak.KeyCloakConfigurer.KEYCLOAK_REALM;
import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Provisions, via the KeyCloak admin REST API, the realm, client and username/password that Polaris
 * trusts. After registration, a client can obtain a token from KeyCloak.
 *
 * Note: In KeyCloak parlance, client is an app and user is a human
 */
public class KeyCloakProvider implements OAuthProvider {

    private static final Logger logger = getLogger(KeyCloakProvider.class);

    /**
     * oauth2-proxy's callback path (see JupyterMfaConfig's --redirect-url).
     */
    public static final String OAUTH2_PROXY_CALLBACK = "/oauth2/callback";
    /**
     * JupyterHub's OAuthenticator callback path (HUB_BASE_URL + "hub/oauth_callback"). The hub now
     * owns the domain root (base_url "/"), so this is "/hub/oauth_callback" rather than under /jupyter/.
     */
    public static final String JUPYTERHUB_CALLBACK   = "/hub/oauth_callback";

    /** Alias of the copied browser flow that this realm binds to, with its OTP step forced on. */
    public static final String BROWSER_MFA_FLOW = "browser-mfa";
    /** Alias of the copied direct-grant flow that this realm binds to, with its OTP step taken out. */
    public static final String DIRECT_GRANT_NO_OTP_FLOW = "direct-grant-no-otp";

    /** 10 hours: how long an issued access token stays valid, so an interactive session lasts a working day. */
    private static final int ACCESS_TOKEN_LIFESPAN_SECONDS = 10 * 60 * 60;
    /**
     * The SSO session bounds cap a token's lifespan, so they must exceed {@link #ACCESS_TOKEN_LIFESPAN_SECONDS}
     * or a freshly-minted token would be clipped short. Given headroom above the 10h token life.
     */
    private static final int SSO_SESSION_LIFESPAN_SECONDS  = 12 * 60 * 60;

    private final ObjectMapper mapper = new ObjectMapper();

    private final String baseUrl;
    private final String adminUser;
    private final String adminPassword;

    /**
     * Create a provider using the configured properties:
     * {@link AfonProperty#KC_HOSTNAME},
     * {@link AfonProperty#KC_BOOTSTRAP_ADMIN_USERNAME},
     * {@link AfonProperty#KC_BOOTSTRAP_ADMIN_PASSWORD}
     * @param properties
     */
    public KeyCloakProvider(AfonPropertyProvider properties) {
        this(properties.getValue(KC_HOSTNAME),
                properties.getValue(KC_BOOTSTRAP_ADMIN_USERNAME),
                properties.getValue(KC_BOOTSTRAP_ADMIN_PASSWORD));
    }

    public KeyCloakProvider(String hostname,
                            String adminUser,
                            String adminPassword) {
        this.baseUrl       = KeyCloakConfigurer.urlFor(hostname, protocolFor(hostname));
        this.adminUser     = adminUser;
        this.adminPassword = adminPassword;
        logger.info("Using KeyCloak URL: {}, admin: {}", this.baseUrl, this.adminUser);
    }

    /**
     * Registers the Polaris user with username and password provided, leaving
     * the email unset.
     */
    @Override
    public void registerPolarisUser(String username, String password) {
        registerPolarisUser(AfonUserProvider.createUser(username, password));
    }

    /**
     * Registers the Polaris user with the configured properties:
     * {@link AfonProperty#USER_POLARIS_ID},
     * {@link AfonProperty#USER_POLARIS_SECRET},
     * {@link AfonProperty#USER_POLARIS_EMAIL}.
     *
     * Registers the browser for the {@link AfonProperty#AWS_JUPYTER_DOMAIN}.
     *
     * Provisions the browser-flow client that oauth2-proxy and JupyterHub share, otherwise clicking
     * "sign in" on the OAuth splash yields KeyCloak's "Client not found", and puts that browser flow
     * behind MFA (see {@link #configureMfa()}).
     */
    @Override
    public void registerPolarisUserAndBrowserClient(AfonPropertyProvider properties) {
        registerPolarisUser(AfonUserProvider.createUser(
                properties.getValue(AfonProperty.USER_POLARIS_ID),
                properties.getValue(AfonProperty.USER_POLARIS_SECRET),
                properties.getValue(AfonProperty.USER_POLARIS_EMAIL, "")));
        registerBrowserClient(properties, properties.getValue(AfonProperty.AWS_JUPYTER_DOMAIN));
        configureMfa();
    }

    /**
     * Registers a Polaris user, also setting the user's (verified) email.
     * A verified email is required for logins that go through oauth2-proxy, which rejects id_tokens
     * whose email is unverified. Pass {@code null}/blank to leave the email unset.
     *
     * Ensures the Polaris realm, client and user all exist.
     * Idempotent: anything that already exists is left untouched.
     */
    @Override
    public void registerPolarisUser(AfonUser user) {
        try {
            String adminToken = adminToken();
            createRealm(adminToken, KEYCLOAK_REALM);
            createConfidentialClient(adminToken, KEYCLOAK_REALM, user.username(), user.password());
            createUser(adminToken, KEYCLOAK_REALM, user);
            logger.info(
                "KeyCloak realm '{}' provisioned with client user '{}' (email '{}')",
                KEYCLOAK_REALM, user.username(), user.email());
        } catch (IOException e) {
            throw new RuntimeException("Failed to provision KeyCloak for Polaris", e);
        }
    }

    /**
     * Obtains an access token for a provisioned Polaris user via the direct-access-grant (ROPC) flow
     * against the {@code polaris} realm. The confidential client that {@link #createConfidentialClient}
     * created for this user shares the user's name and secret, hence the doubled credentials.
     */
    @Override
    public String userToken(String username, String password) throws IOException {
        logger.info("Getting token for {}/{}", username, password);
        String uri = format("%s/realms/%s/protocol/openid-connect/token", baseUrl, KEYCLOAK_REALM);
        String body = form(Map.of(
            "grant_type",    "password",
            "client_id",     username,
            "client_secret", password,
            "username",      username,
            "password",      password
        ));
        HttpPost post = new HttpPost(uri);
        post.setHeader("Content-Type", "application/x-www-form-urlencoded");
        post.setEntity(new StringEntity(body));
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            return client.execute(post, response -> {
                if (response.getCode() != HttpStatus.SC_OK) {
                    throw new IOException("Could not obtain token for user " + username + ": HTTP " + response.getCode());
                }
                Map<?, ?> json = mapper.readValue(response.getEntity().getContent(), Map.class);
                return (String) json.get("access_token");
            });
        }
    }


    /**
     * Authenticates against the 'master' realm as the bootstrap admin.
     *
     * Note: This is the 'master' realm so only the admin user can call this.
     * @return An access token for the admin REST API.
     * @throws IOException
     */
    @Override
    public String adminToken() throws IOException {
        return getToken("master");
    }

    /**
     * Authenticates against the given realm, eg 'polaris'.
     *
     * @param realm the realm to authenticate against.
     * @return An access token
     * @throws IOException
     */
    @Override
    public String getToken(String realm) throws IOException {
        String uri = format("%s/realms/%s/protocol/openid-connect/token", baseUrl, realm);
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
                    Map<?, ?> json = mapper.readValue(response.getEntity().getContent(), Map.class);
                    return (String) json.get("access_token");
                });
        }
    }

    /**
     * Creates the realm if absent and, either way, converges its token lifespans. Polaris validates
     * the bearer JWT offline (signature + {@code exp}), so the access token's own lifespan is what
     * decides how long a pod can talk to Polaris; KeyCloak's 5-minute default is far too short. The
     * SSO session bounds are raised in step because they otherwise cap the access token.
     *
     * <p>Upserts rather than create-if-absent: the realm outlives a cluster rebuild, so an
     * already-existing one (created by an earlier deploy with the old 5-minute default) is updated to
     * these lifespans rather than left stale.
     */
    @Override
    public void createRealm(String adminToken, String realm) throws IOException {
        String json = mapper.writeValueAsString(Map.of(
            "realm", realm,
            "enabled", true,
            "accessTokenLifespan", ACCESS_TOKEN_LIFESPAN_SECONDS,
            "ssoSessionMaxLifespan", SSO_SESSION_LIFESPAN_SECONDS,
            "ssoSessionIdleTimeout", SSO_SESSION_LIFESPAN_SECONDS
        ));
        HttpResponse created = postJson(adminToken, format("%s/admin/realms", baseUrl), json, "realm " + realm);
        if (created != null && created.getCode() == HttpStatus.SC_CONFLICT) {
            if (isFailure(putJson(adminToken, format("%s/admin/realms/%s", baseUrl, realm), json, "realm " + realm))) {
                logger.warn("Could not update KeyCloak realm '{}'", realm);
            } else {
                logger.info("Updated existing KeyCloak realm '{}' token lifespans", realm);
            }
        } else if (isFailure(created)) {
            logger.warn("Could not create KeyCloak realm '{}'", realm);
        }
    }

    /**
     * Creates a confidential client with direct access grants enabled so that the user's
     * username/password can be exchanged for a token.
     */
    @Override
    public void createConfidentialClient(
        String adminToken,
        String realm,
        String clientId,
        String clientSecret
    ) throws IOException {
        String json = mapper.writeValueAsString(Map.of(
            "clientId", clientId,
            "secret", clientSecret,
            "enabled", true,
            "protocol", "openid-connect",
            "publicClient", false,
            "serviceAccountsEnabled", true,
            "directAccessGrantsEnabled", true,
            "standardFlowEnabled", false
        ));
        HttpResponse created = postJson(
            adminToken, format("%s/admin/realms/%s/clients", baseUrl, realm), json, "client " + clientId);
        if (created != null && created.getCode() == HttpStatus.SC_CONFLICT) {
            String uuid = clientUuid(adminToken, realm, clientId);
            if (uuid == null
                || isFailure(putJson(adminToken, format("%s/admin/realms/%s/clients/%s", baseUrl, realm, uuid),
                                     json, "client " + clientId))) {
                logger.warn("Could not update confidential client {}", clientId);
            } else {
                logger.info("Updated existing KeyCloak confidential client {}", clientId);
            }
        } else if (isFailure(created)) {
            logger.warn("Could not create confidential client {}", clientId);
        }
        // A client_credentials grant carries preferred_username="service-account-<clientId>", which is
        // not a Polaris principal. Stamp the bare clientId onto the dedicated 'polaris_principal' claim
        // Polaris maps on, so this service client resolves to its bootstrapped principal (e.g. "root").
        ensureClientMapper(adminToken, realm, clientId, hardcodedPrincipalMapper(clientId));
    }

    /** The dedicated claim Polaris reads the principal name from (see KeyCloakHelper). */
    private static final String PRINCIPAL_CLAIM = "polaris_principal";

    /**
     * A hardcoded-claim mapper that sets {@link #PRINCIPAL_CLAIM} to {@code clientId}, for
     * client_credentials (service-account) tokens whose {@code preferred_username} is unusable.
     */
    private static Map<String, Object> hardcodedPrincipalMapper(String clientId) {
        return Map.of(
            "name", PRINCIPAL_CLAIM + "-from-clientId",
            "protocol", "openid-connect",
            "protocolMapper", "oidc-hardcoded-claim-mapper",
            "config", Map.of(
                "claim.name", PRINCIPAL_CLAIM,
                "claim.value", clientId,
                "jsonType.label", "String",
                "access.token.claim", "true",
                "id.token.claim", "false",
                "userinfo.token.claim", "false"
            ));
    }

    /**
     * A user-property mapper that copies the logged-in user's {@code username} into
     * {@link #PRINCIPAL_CLAIM}, so a human's browser-login token resolves to their own Polaris principal.
     */
    private static Map<String, Object> usernamePrincipalMapper() {
        return Map.of(
            "name", PRINCIPAL_CLAIM + "-from-username",
            "protocol", "openid-connect",
            "protocolMapper", "oidc-usermodel-property-mapper",
            "config", Map.of(
                "user.attribute", "username",
                "claim.name", PRINCIPAL_CLAIM,
                "jsonType.label", "String",
                "access.token.claim", "true",
                "id.token.claim", "false",
                "userinfo.token.claim", "false"
            ));
    }

    /**
     * Idempotently attaches a protocol mapper to a client via the protocol-mappers sub-resource. A
     * client PUT does not reliably update mappers on an existing client, so mappers are managed here
     * on their own endpoint; an already-present mapper (409) is left as-is.
     */
    private void ensureClientMapper(String adminToken, String realm, String clientId,
                                    Map<String, Object> mapper) throws IOException {
        String uuid = clientUuid(adminToken, realm, clientId);
        if (uuid == null) {
            logger.warn("Cannot attach protocol mapper: client {} not found", clientId);
            return;
        }
        String uri  = format("%s/admin/realms/%s/clients/%s/protocol-mappers/models", baseUrl, realm, uuid);
        String json = this.mapper.writeValueAsString(mapper);
        postJson(adminToken, uri, json, "protocol mapper " + mapper.get("name") + " on " + clientId);
    }

    private boolean isFailure(HttpResponse response) {
        return response == null || response.getCode() / 100 != 2;
    }

    /**
     * Registers the browser-flow client for the Jupyter domain using the OAuth2 client configured in properties.
     *
     * @param properties
     * @param jupyterDomain
     */
    @Override
    public void registerBrowserClient(AfonPropertyProvider properties, String jupyterDomain) {
        registerBrowserClient(
                properties.getValue(AfonProperty.OAUTH2_CLIENT_ID),
                properties.getValue(AfonProperty.OAUTH2_CLIENT_SECRET),
                List.of(
                        "https://" + jupyterDomain + OAUTH2_PROXY_CALLBACK,
                        "https://" + jupyterDomain + JUPYTERHUB_CALLBACK));
    }

    /**
     * Ensures the realm exists and (idempotently) provisions the confidential, browser-flow client that
     * oauth2-proxy and JupyterHub use to log users in. Unlike {@link #createConfidentialClient} (a
     * direct-grant client for Polaris), this client has the authorization-code flow enabled and
     * registered redirect URIs, so the browser redirect from oauth2-proxy resolves - otherwise
     * KeyCloak returns "Client not found".
     *
     * <p>Idempotent in the converging sense: an already-existing client is overwritten with the URIs
     * below rather than left alone, since KeyCloak's database survives a cluster rebuild and would
     * otherwise pin the callbacks of whichever deploy first created it.
     *
     * @param redirectUris the exact callback URLs, e.g.
     *                     {@code https://jupyter.example/oauth2/callback} (oauth2-proxy) and
     *                     {@code https://jupyter.example/hub/oauth_callback} (JupyterHub).
     */
    @Override
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
    @Override
    public void createBrowserClient(String adminToken,
                                    String realm,
                                    String clientId,
                                    String clientSecret,
                                    List<String> redirectUris
    ) throws IOException {
        String json = mapper.writeValueAsString(Map.ofEntries(
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
        HttpResponse created = postJson(
            adminToken, format("%s/admin/realms/%s/clients", baseUrl, realm), json, "browser client " + clientId);
        if (created != null && created.getCode() == HttpStatus.SC_CONFLICT) {
            // KeyCloak's DB outlives the cluster, so a client provisioned by an earlier deploy survives
            // with whatever redirect URIs it was created with. Creating-if-absent would leave those
            // stale — the browser then arrives with an unregistered redirect_uri and KeyCloak rejects
            // the login with "Invalid parameter: redirect_uri". Overwrite so the client converges on
            // the URIs this deploy actually uses.
            updateBrowserClient(adminToken, realm, clientId, json);
        } else if (isFailure(created)) {
            logger.warn("Could not create browser client {}", clientId);
        }
        // Human logins go through this client; copy their username into the claim Polaris maps on so
        // the captured login token resolves to that user's Polaris principal (e.g. "will").
        ensureClientMapper(adminToken, realm, clientId, usernamePrincipalMapper());
    }

    /**
     * Overwrites an existing client's representation, looking up its internal UUID by {@code clientId}
     * (the admin API keys updates on the UUID, not the human-readable client ID).
     */
    private void updateBrowserClient(String adminToken, String realm, String clientId, String json)
        throws IOException {
        String uuid = clientUuid(adminToken, realm, clientId);
        if (uuid == null) {
            logger.warn("Browser client {} reported as existing but could not be looked up", clientId);
            return;
        }
        String uri = format("%s/admin/realms/%s/clients/%s", baseUrl, realm, uuid);
        if (isFailure(putJson(adminToken, uri, json, "browser client " + clientId))) {
            logger.warn("Could not update browser client {}", clientId);
        } else {
            logger.info("Updated existing KeyCloak browser client {}", clientId);
        }
    }

    @Override
    public void configureMfa() {
        try {
            configureMfa(adminToken(), KEYCLOAK_REALM);
        } catch (IOException e) {
            throw new RuntimeException("Failed to configure MFA on KeyCloak", e);
        }
    }

    /**
     * Makes every browser login to this realm present a TOTP second factor, while leaving headless
     * logins able to get a token from a username/password alone.
     *
     * <p>Both halves are needed, and the second is not optional. Forcing MFA on the browser flow makes
     * humans enrol an authenticator app - and KeyCloak's built-in direct-grant flow then demands OTP
     * from exactly those users who have one, so a Spark or Trino job running as a human would start
     * failing with "Invalid user credentials". The direct grant therefore gets its OTP step taken out:
     * the second factor guards interactive logins (oauth2-proxy, JupyterHub), and a headless login -
     * which has nobody to read a phone - is guarded by its confidential client's secret instead.
     *
     * <p>KeyCloak refuses to modify its own built-in flows, so each is copied first and the realm
     * bound to the copy. Idempotent: re-running against a converged realm re-copies nothing (the copy
     * 409s) and issues no execution updates.
     */
    @Override
    public void configureMfa(String adminToken, String realm) throws IOException {
        requireOtpForBrowserLogins(adminToken, realm);
        skipOtpForDirectGrants(adminToken, realm);
    }

    /** Binds the realm to a browser flow whose OTP step is mandatory rather than opt-in. */
    private void requireOtpForBrowserLogins(String adminToken, String realm) throws IOException {
        copyFlow(adminToken, realm, DefaultAuthenticationFlows.BROWSER_FLOW, BROWSER_MFA_FLOW);
        List<Map<String, Object>> changed =
            KeyCloakMfa.requireOtp(flowExecutions(adminToken, realm, BROWSER_MFA_FLOW));
        updateFlowExecutions(adminToken, realm, BROWSER_MFA_FLOW, changed);
        bindFlow(adminToken, realm, "browserFlow", BROWSER_MFA_FLOW);
        logger.info(
            "KeyCloak realm '{}' browser flow '{}' requires OTP ({} execution(s) updated)",
            realm, BROWSER_MFA_FLOW, changed.size());
    }

    /** Binds the realm to a direct-grant flow with no OTP step, so headless logins keep working. */
    private void skipOtpForDirectGrants(String adminToken, String realm) throws IOException {
        copyFlow(adminToken, realm, DefaultAuthenticationFlows.DIRECT_GRANT_FLOW, DIRECT_GRANT_NO_OTP_FLOW);
        List<Map<String, Object>> changed =
            KeyCloakMfa.skipOtp(flowExecutions(adminToken, realm, DIRECT_GRANT_NO_OTP_FLOW));
        updateFlowExecutions(adminToken, realm, DIRECT_GRANT_NO_OTP_FLOW, changed);
        bindFlow(adminToken, realm, "directGrantFlow", DIRECT_GRANT_NO_OTP_FLOW);
        logger.info(
            "KeyCloak realm '{}' direct grant flow '{}' skips OTP ({} execution(s) updated)",
            realm, DIRECT_GRANT_NO_OTP_FLOW, changed.size());
    }

    /**
     * Copies one of KeyCloak's built-in flows. A 409 means an earlier deploy already made the copy,
     * which the caller then converges rather than replaces - deleting and re-copying would leave the
     * realm bound to a missing flow in between.
     */
    private void copyFlow(String adminToken, String realm, String from, String to) throws IOException {
        String uri = format(
            "%s/admin/realms/%s/authentication/flows/%s/copy",
            baseUrl, realm, pathSegment(from));
        postJson(adminToken, uri, mapper.writeValueAsString(Map.of("newName", to)), "copy of flow " + from);
    }

    /**
     * Encodes a value for use as a URL <em>path</em> segment. {@link URLEncoder} targets query
     * strings, where a space becomes "+"; in a path that is a literal plus, so KeyCloak would 404 on
     * built-in aliases such as "direct grant".
     */
    private static String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** A flow's executions, depth-first and each carrying its nesting level, as {@link KeyCloakMfa} expects. */
    private List<Map<String, Object>> flowExecutions(String adminToken, String realm, String flowAlias)
        throws IOException {
        String  uri = format("%s/admin/realms/%s/authentication/flows/%s/executions",
                             baseUrl, realm, pathSegment(flowAlias));
        HttpGet get = new HttpGet(uri);
        get.setHeader("Authorization", "Bearer " + adminToken);
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            return client.execute(get, response -> {
                if (response.getCode() != HttpStatus.SC_OK) {
                    throw new IOException(format(
                        "Could not read executions of KeyCloak flow '%s': HTTP %d", flowAlias, response.getCode()));
                }
                return mapper.readValue(response.getEntity().getContent(), List.class);
            });
        }
    }

    /**
     * Writes changed executions back, in the order given. The admin API keys these on the execution's
     * own id carried in the body, so each whole representation is PUT to the flow's executions
     * collection.
     */
    private void updateFlowExecutions(String adminToken, String realm, String flowAlias,
                                      List<Map<String, Object>> executions) throws IOException {
        String uri = format("%s/admin/realms/%s/authentication/flows/%s/executions",
                            baseUrl, realm, pathSegment(flowAlias));
        for (Map<String, Object> execution : executions) {
            String what = execution.get("displayName") + " -> " + execution.get("requirement");
            if (isFailure(putJson(adminToken, uri, mapper.writeValueAsString(execution), "execution " + what))) {
                throw new IOException("Could not set KeyCloak flow execution " + what);
            }
        }
    }

    /**
     * Points one of the realm's flow bindings at a flow. Done after that flow's executions are set, so
     * there is no window in which logins run a half-converged flow.
     */
    private void bindFlow(String adminToken, String realm, String binding, String flowAlias) throws IOException {
        String json = mapper.writeValueAsString(Map.of("realm", realm, binding, flowAlias));
        if (isFailure(putJson(adminToken, format("%s/admin/realms/%s", baseUrl, realm), json, "realm " + realm))) {
            throw new IOException(format(
                "Could not bind KeyCloak realm %s %s to flow %s", realm, binding, flowAlias));
        }
    }

    /** The internal UUID KeyCloak assigns a client, or null when no such client exists. */
    private String clientUuid(String adminToken, String realm, String clientId) throws IOException {
        String uri = format("%s/admin/realms/%s/clients?clientId=%s",
                            baseUrl, realm, URLEncoder.encode(clientId, StandardCharsets.UTF_8));
        HttpGet get = new HttpGet(uri);
        get.setHeader("Authorization", "Bearer " + adminToken);
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            return client.execute(get, response -> {
                if (response.getCode() != HttpStatus.SC_OK) {
                    return null;
                }
                List<?> found = mapper.readValue(response.getEntity().getContent(), List.class);
                if (found.isEmpty()) {
                    return null;
                }
                return (String) ((Map<?, ?>) found.get(0)).get("id");
            });
        }
    }

    private HttpResponse putJson(String adminToken, String uri, String json, String what) throws IOException {
        logger.info("Calling PUT {} with: \n{}", uri, json);
        HttpPut put = new HttpPut(uri);
        put.setHeader("Authorization", "Bearer " + adminToken);
        put.setHeader("Content-Type", "application/json");
        put.setEntity(new StringEntity(json, StandardCharsets.UTF_8));
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            return client.execute(put, response -> {
                logger.info("PUT {} resulted in HTTP code {}", uri, response.getCode());
                return response;
            });
        }
    }

    @Override
    public void createUser(String adminToken,
                           String realm,
                           AfonUser user
    ) throws IOException {
        Map<String, Object> userObject = new HashMap<>();
        userObject.put("username", user.username());
        userObject.put("enabled", true);
        // emailVerified is always true because oauth2-proxy rejects logins whose id_token email is
        // unverified; there is no SMTP verification flow behind this Keycloak.
        userObject.put("emailVerified", true);
        if (user.email() != null && !user.email().isBlank()) {
            userObject.put("email", user.email());
        }
        if (user.firstName() != null && !user.firstName().isBlank()) {
            userObject.put("firstName", user.firstName());
        }
        if (user.lastName() != null && !user.lastName().isBlank()) {
            userObject.put("lastName", user.lastName());
        }
        userObject.put(
            "credentials", List.of(Map.of(
                "type", "password",
                "value", user.password(),
                "temporary", false
            )));
        String json = mapper.writeValueAsString(userObject);
        int    code = postJson(adminToken, format("%s/admin/realms/%s/users", baseUrl, realm), json, "user " + user.username()).getCode();
        // A 409 on POST /users is ambiguous: it means the username already exists (genuinely
        // idempotent - fine) OR that some other unique field collides, typically a duplicate email
        // when the realm has duplicateEmailsAllowed=false. In the latter case the user is NOT created,
        // so silently treating the 409 as success (as postJson does) leaves logins failing with
        // user_not_found. Confirm the user really exists; if not, fail loudly with the likely cause.
        if (code == HttpStatus.SC_CONFLICT && !userExists(adminToken, realm, user.username())) {
            throw new IOException(format(
                "Could not create KeyCloak user '%s' in realm '%s': HTTP 409 but no user with that "
                + "username exists. The email '%s' is most likely already held by another user "
                + "(realm duplicateEmailsAllowed=false).", user.username(), realm, user.email()));
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
            return client.execute(
                get, response -> {
                    if (response.getCode() != HttpStatus.SC_OK) {
                        return false;
                    }
                    List<?> users = mapper.readValue(response.getEntity().getContent(), List.class);
                    return !users.isEmpty();
                });
        }
    }

    private HttpResponse postJson(String adminToken, String uri, String json, String what) throws IOException {
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
                        logger.warn("KeyCloak {} already exists", what);
                    } else {
                        throw new IOException(format("Could not create KeyCloak %s: HTTP %d", what, code));
                    }
                    return response;
                });
        }
    }

    /**
     * Convenience check used by callers to wait until KeyCloak is serving requests.
     */
    @Override
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

}