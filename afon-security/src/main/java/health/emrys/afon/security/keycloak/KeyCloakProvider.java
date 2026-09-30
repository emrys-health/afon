package health.emrys.afon.security.keycloak;

import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUser;
import health.emrys.afon.rest.HttpResponses;
import health.emrys.afon.rest.RestClient;
import health.emrys.afon.security.IdentityProvider;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.jspecify.annotations.NonNull;
import org.keycloak.models.utils.DefaultAuthenticationFlows;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.config.AfonProperty.AFON_THEME;
import static health.emrys.afon.rest.HttpResponses.isFailure;
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
public class KeyCloakProvider implements IdentityProvider {

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
    /**
     * The default {@link AfonProperty#AFON_THEME}
     */
    public static final String DEFAULT_AFON_THEME = "emrys-hdrs";

    /**
     * The callback URIs, for the given domain, eg
     * {@code https://jupyter.example/oauth2/callback} (oauth2-proxy) and
     * {@code https://jupyter.example/hub/oauth_callback} (JupyterHub).
    */
    public static @NonNull List<String> callbackURIs(String jupyterDomain) {
        return List.of(
                "https://" + jupyterDomain + OAUTH2_PROXY_CALLBACK,
                "https://" + jupyterDomain + JUPYTERHUB_CALLBACK);
    }

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

    private final RestClient restClient;
    private final String adminUsername;
    private final String adminPassword;
    private final String loginTheme;

    /**
     * Creates a provider for token requests only ({@link #userToken}, {@link #clientToken}), which need no
     * bootstrap admin credentials. The admin operations fail without them.
     */
    public KeyCloakProvider(String hostname) {
        this(hostname, null, null);
    }

    /**
     * Creates a provider with bootstrap admin credentials.
     */
    public KeyCloakProvider(String hostname,
                            String adminUsername,
                            String adminPassword) {
        this(hostname, adminUsername, adminPassword, DEFAULT_AFON_THEME);
    }

    /**
     * Creates a provider using the configured properties:
     * {@link AfonProperty#OIDC_DOMAIN_NAME},
     * {@link AfonProperty#KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME},
     * {@link AfonProperty#KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD},
     * {@link AfonProperty#AFON_THEME}
     * @param properties
     */
    public KeyCloakProvider(AfonPropertyProvider properties) {
        this(properties.getValue(OIDC_DOMAIN_NAME),
                properties.getValue(KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME),
                properties.getValue(KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD),
                properties.getValue(AFON_THEME, DEFAULT_AFON_THEME));
    }

    KeyCloakProvider(String hostname,
                            String adminUsername,
                            String adminPassword,
                            String loginTheme) {
        this.restClient = new RestClient(KeyCloakConfigurer.urlFor(hostname, protocolFor(hostname)));
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.loginTheme    = loginTheme;
        logger.info("Using KeyCloak URL: {}", restClient.getBaseUrl());
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
        String keycloakRealm = KEYCLOAK_REALM;
        try {
            String adminToken = adminToken();
            createRealm(adminToken, keycloakRealm);
            createConfidentialClient(adminToken, keycloakRealm, user.username(), user.password());
            createUser(adminToken, keycloakRealm, user);
            logger.info("KeyCloak realm '{}' provisioned with client user '{}' (email '{}')",
                    keycloakRealm, user.username(), user.email());
        } catch (IOException e) {
            String message = String.format("Failed to provision KeyCloak realm '%s' with client user '%s' (email '%s')",
                    keycloakRealm, user.username(), user.email());
            throw new RuntimeException(message, e);
        }
    }

    /**
     * Obtains an access token for a provisioned Polaris user via the direct-access-grant (ROPC) flow
     * against the {@code polaris} realm. The confidential client that {@link #createConfidentialClient}
     * created for this user shares the user's name and secret, hence the doubled credentials.
     */
    @Override
    public String userToken(String username, String password) throws IOException {
        logger.info("Getting token for user '{}'", username);
        return polarisRealmToken(Map.of(
            "grant_type",    "password",
            "client_id",     username,
            "client_secret", password,
            "username",      username,
            "password",      password
        ));
    }

    /**
     * Obtains an access token for a confidential client via the client-credentials flow against the
     * {@code polaris} realm, eg for the Polaris admin client ({@code POLARIS_ID}), which is a client but
     * not a user. The 'polaris_principal' mapper on the client resolves the token to the Polaris principal
     * of the same name (see {@link #createConfidentialClient}).
     */
    @Override
    public String clientToken(String clientId, String clientSecret) throws IOException {
        logger.info("Getting token for client '{}'", clientId);
        return polarisRealmToken(Map.of(
            "grant_type",    "client_credentials",
            "client_id",     clientId,
            "client_secret", clientSecret
        ));
    }

    /**
     * @throws IOException           on an I/O error or a server error (5xx), which may be transient, so
     *                               callers may retry (eg {@code PolarisRestClient#getAccessToken})
     * @throws IllegalStateException on any other failure (eg 401 on bad credentials), or a response with
     *                               no access token, which retrying will not fix
     * @see HttpResponses#parseAccessToken
     */
    private String polarisRealmToken(Map<String, String> form) throws IOException {
        String path = format("/realms/%s/protocol/openid-connect/token", KEYCLOAK_REALM);
        return restClient.postForm(path, form, HttpResponses::parseAccessToken);
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
     * @throws IllegalStateException if this provider has no bootstrap admin credentials, eg when created
     *                               with {@link #KeyCloakProvider(String)} for token requests only
     */
    @Override
    public String getToken(String realm) throws IOException {
        if (adminUsername == null || adminUsername.isBlank() || adminPassword == null || adminPassword.isBlank()) {
            throw new IllegalStateException(format(
                "No KeyCloak bootstrap admin credentials to authenticate against realm '%s': set "
                    + "KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME and KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD", realm));
        }
        String path = format("/realms/%s/protocol/openid-connect/token", realm);
        logger.info("Retrieving token from {}", path);
        return restClient.postForm(path, Map.of(
            "grant_type", "password",
            "client_id", "admin-cli",
            "username", adminUsername,
            "password", adminPassword
        ), HttpResponses::parseAccessToken);
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
    public HttpResponse createRealm(String adminToken, String realm) throws IOException {
        Map<String, Object> realmConfig = new LinkedHashMap<>();
        realmConfig.put("realm", realm);
        realmConfig.put("enabled", true);
        realmConfig.put("accessTokenLifespan", ACCESS_TOKEN_LIFESPAN_SECONDS);
        realmConfig.put("ssoSessionMaxLifespan", SSO_SESSION_LIFESPAN_SECONDS);
        realmConfig.put("ssoSessionIdleTimeout", SSO_SESSION_LIFESPAN_SECONDS);
        // The active theme (AFON_THEME, default emrys-hdrs), mounted into
        // /opt/keycloak/themes by KeyCloakConfig. KeyCloak falls back to
        // its default theme where the theme isn't installed (eg local
        // acceptance-test instances).
        realmConfig.put("loginTheme", loginTheme);
        // Branding text lives with the theme, in afon-theme's themes/<theme>/realm.properties, so a
        // new theme can supply its own display name without touching this class. Left unset (falling
        // back to KeyCloak's own default of showing the realm id) if the theme defines neither.
        Properties branding = loadThemeProperties(loginTheme, "realm.properties");
        String displayName = branding.getProperty("displayName");
        if (displayName != null) {
            realmConfig.put("displayName", displayName);
        }
        String displayNameHtml = branding.getProperty("displayNameHtml");
        if (displayNameHtml != null) {
            realmConfig.put("displayNameHtml", displayNameHtml);
        }
        String json = mapper.writeValueAsString(realmConfig);
        HttpResponse created = postJson(adminToken, "/admin/realms", json, "realm " + realm);
        if (created != null && created.getCode() == HttpStatus.SC_CONFLICT) {
            if (isFailure(putJson(adminToken, format("/admin/realms/%s", realm), json, "realm " + realm))) {
                logger.warn("Could not update KeyCloak realm '{}'", realm);
            } else {
                logger.info("Updated existing KeyCloak realm '{}' token lifespans", realm);
            }
        } else if (isFailure(created)) {
            logger.warn("Could not create KeyCloak realm '{}'", realm);
        }
        return created;
    }

    /**
     * Creates a confidential client with direct access grants enabled so that the user's
     * username/password can be exchanged for a token.
     */
    @Override
    public HttpResponse createConfidentialClient(
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
            adminToken, format("/admin/realms/%s/clients", realm), json, "client " + clientId);
        if (created != null && created.getCode() == HttpStatus.SC_CONFLICT) {
            String uuid = clientUuid(adminToken, realm, clientId);
            if (uuid == null
                || isFailure(putJson(adminToken, format("/admin/realms/%s/clients/%s", realm, uuid),
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
        return ensureClientMapper(adminToken, realm, clientId, hardcodedPrincipalMapper(clientId));
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
    private HttpResponse ensureClientMapper(String adminToken, String realm, String clientId,
                                            Map<String, Object> mapper) throws IOException {
        String uuid = clientUuid(adminToken, realm, clientId);
        if (uuid == null) {
            logger.warn("Cannot attach protocol mapper: client {} not found", clientId);
            return null;
        }
        String uri  = format("/admin/realms/%s/clients/%s/protocol-mappers/models", realm, uuid);
        String json = this.mapper.writeValueAsString(mapper);
        return postJson(adminToken, uri, json, "protocol mapper " + mapper.get("name") + " on " + clientId);
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
     * @param clientId the OAUTH2 client id
     * @param clientSecret the OAUTH2 client secret
     * @param redirectUris the {@link #callbackURIs(String)}
     */
    @Override
    public List<HttpResponse> registerBrowserClient(String clientId, String clientSecret, List<String> redirectUris) {
        List<HttpResponse> responses = new ArrayList<>();
        try {
            String adminToken = adminToken();
            responses.add(createRealm(adminToken, KEYCLOAK_REALM));
            responses.addAll(createBrowserClient(adminToken, KEYCLOAK_REALM, clientId, clientSecret, redirectUris));
            logger.info(
                "KeyCloak realm '{}' provisioned with browser client '{}' (redirects {})",
                KEYCLOAK_REALM, clientId, redirectUris);
        } catch (IOException e) {
            throw new RuntimeException("Failed to provision KeyCloak browser client", e);
        }
        return responses;
    }

    /**
     * Creates a confidential client for the OAuth2 authorization-code (browser) flow: standard flow
     * on, direct grants and service accounts off, with the given redirect URIs whitelisted.
     */
    @Override
    public List<HttpResponse> createBrowserClient(String adminToken,
                                                  String realm,
                                                  String clientId,
                                                  String clientSecret,
                                                  List<String> redirectUris
    ) throws IOException {
        List<HttpResponse> responses = new ArrayList<>();
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
            adminToken, format("/admin/realms/%s/clients", realm), json, "browser client " + clientId);
        responses.add(created);
        if (created != null && created.getCode() == HttpStatus.SC_CONFLICT) {
            // KeyCloak's DB outlives the cluster, so a client provisioned by an earlier deploy survives
            // with whatever redirect URIs it was created with. Creating-if-absent would leave those
            // stale — the browser then arrives with an unregistered redirect_uri and KeyCloak rejects
            // the login with "Invalid parameter: redirect_uri". Overwrite so the client converges on
            // the URIs this deploy actually uses.
            responses.add(updateBrowserClient(adminToken, realm, clientId, json));
        } else if (isFailure(created)) {
            logger.warn("Could not create browser client {}", clientId);
        }
        // Human logins go through this client; copy their username into the claim Polaris maps on so
        // the captured login token resolves to that user's Polaris principal (e.g. "will").
        responses.add(ensureClientMapper(adminToken, realm, clientId, usernamePrincipalMapper()));
        return responses;
    }

    /**
     * Overwrites an existing client's representation, looking up its internal UUID by {@code clientId}
     * (the admin API keys updates on the UUID, not the human-readable client ID).
     */
    private HttpResponse updateBrowserClient(String adminToken, String realm, String clientId, String json)
        throws IOException {
        String uuid = clientUuid(adminToken, realm, clientId);
        if (uuid == null) {
            logger.warn("Browser client {} reported as existing but could not be looked up", clientId);
            return null;
        }
        String uri = format("/admin/realms/%s/clients/%s", realm, uuid);
        HttpResponse response = putJson(adminToken, uri, json, "browser client " + clientId);
        if (isFailure(response)) {
            logger.warn("Could not update browser client {}", clientId);
        } else {
            logger.info("Updated existing KeyCloak browser client {}", clientId);
        }
        return response;
    }

    @Override
    public List<HttpResponse> configureMfa() {
        try {
            return configureMfa(adminToken(), KEYCLOAK_REALM);
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
    public List<HttpResponse> configureMfa(String adminToken, String realm) throws IOException {
        List<HttpResponse> responses = new  ArrayList<>();
        responses.addAll(requireOtpForBrowserLogins(adminToken, realm));
        responses.addAll(skipOtpForDirectGrants(adminToken, realm));
        return responses;
    }

    /**
     * Binds the realm to a browser flow whose OTP step is mandatory rather than opt-in.
     */
    private List<HttpResponse> requireOtpForBrowserLogins(String adminToken, String realm) throws IOException {
        List<HttpResponse> responses = new  ArrayList<>();
        responses.add(copyFlow(adminToken, realm, DefaultAuthenticationFlows.BROWSER_FLOW, BROWSER_MFA_FLOW));
        List<Map<String, Object>> changed =
            KeyCloakMfa.requireOtp(flowExecutions(adminToken, realm, BROWSER_MFA_FLOW));
        responses.addAll(updateFlowExecutions(adminToken, realm, BROWSER_MFA_FLOW, changed));
        responses.add(bindFlow(adminToken, realm, "browserFlow", BROWSER_MFA_FLOW));
        logger.info(
            "KeyCloak realm '{}' browser flow '{}' requires OTP ({} execution(s) updated)",
            realm, BROWSER_MFA_FLOW, changed.size());
        return responses;
    }

    /**
     * Binds the realm to a direct-grant flow with no OTP step, so headless logins keep working.
     */
    private List<HttpResponse> skipOtpForDirectGrants(String adminToken, String realm) throws IOException {
        copyFlow(adminToken, realm, DefaultAuthenticationFlows.DIRECT_GRANT_FLOW, DIRECT_GRANT_NO_OTP_FLOW);
        List<Map<String, Object>> changed =
            KeyCloakMfa.skipOtp(flowExecutions(adminToken, realm, DIRECT_GRANT_NO_OTP_FLOW));
        List<HttpResponse> responses = updateFlowExecutions(adminToken, realm, DIRECT_GRANT_NO_OTP_FLOW, changed);
        responses.add(bindFlow(adminToken, realm, "directGrantFlow", DIRECT_GRANT_NO_OTP_FLOW));
        logger.info(
            "KeyCloak realm '{}' direct grant flow '{}' skips OTP ({} execution(s) updated)",
            realm, DIRECT_GRANT_NO_OTP_FLOW, changed.size());
        return responses;
    }

    /**
     * Copies one of KeyCloak's built-in flows. A 409 means an earlier deploy already made the copy,
     * which the caller then converges rather than replaces - deleting and re-copying would leave the
     * realm bound to a missing flow in between.
     */
    private HttpResponse copyFlow(String adminToken, String realm, String from, String to) throws IOException {
        String uri = format("/admin/realms/%s/authentication/flows/%s/copy", realm, pathSegment(from));
        return postJson(adminToken, uri, mapper.writeValueAsString(Map.of("newName", to)), "copy of flow " + from);
    }

    /**
     * Encodes a value for use as a URL <em>path</em> segment. {@link URLEncoder} targets query
     * strings, where a space becomes "+"; in a path that is a literal plus, so KeyCloak would 404 on
     * built-in aliases such as "direct grant".
     */
    private static String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * A theme's {@code themes/<theme>/<fileName>} properties file, from afon-theme on the classpath.
     * Empty (not an error) if the theme doesn't define one — callers fall back to their own defaults.
     */
    private static Properties loadThemeProperties(String theme, String fileName) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = KeyCloakProvider.class.getResourceAsStream(
                "/themes/" + theme + "/" + fileName)) {
            if (in != null) {
                properties.load(in);
            }
        }
        return properties;
    }

    /** A flow's executions, depth-first and each carrying its nesting level, as {@link KeyCloakMfa} expects. */
    private List<Map<String, Object>> flowExecutions(String adminToken, String realm, String flowAlias)
        throws IOException {
        String  uri = format("/admin/realms/%s/authentication/flows/%s/executions", realm, pathSegment(flowAlias));
        return restClient.get(uri, adminToken, response -> {
            if (response.getCode() != HttpStatus.SC_OK) {
                throw new IOException(format(
                    "Could not read executions of KeyCloak flow '%s': HTTP %d", flowAlias, response.getCode()));
            }
            return mapper.readValue(response.getEntity().getContent(), List.class);
        });
    }

    /**
     * Writes changed executions back, in the order given. The admin API keys these on the execution's
     * own id carried in the body, so each whole representation is PUT to the flow's executions
     * collection.
     */
    private List<HttpResponse> updateFlowExecutions(String adminToken, String realm, String flowAlias,
                                                    List<Map<String, Object>> executions) throws IOException {
        String uri = format("/admin/realms/%s/authentication/flows/%s/executions", realm, pathSegment(flowAlias));
        List<HttpResponse> responses = new ArrayList<>();
        for (Map<String, Object> execution : executions) {
            String what = execution.get("displayName") + " -> " + execution.get("requirement");
            HttpResponse response = putJson(adminToken, uri, mapper.writeValueAsString(execution), "execution " + what);
            responses.add(response);
            if (isFailure(response)) {
                throw new IOException("Could not set KeyCloak flow execution " + what);
            }
        }
        return responses;
    }

    /**
     * Points one of the realm's flow bindings at a flow. Done after that flow's executions are set, so
     * there is no window in which logins run a half-converged flow.
     */
    private HttpResponse bindFlow(String adminToken, String realm, String binding, String flowAlias) throws IOException {
        String json = mapper.writeValueAsString(Map.of("realm", realm, binding, flowAlias));
        HttpResponse response = putJson(adminToken, format("/admin/realms/%s", realm), json, "realm " + realm);
        if (isFailure(response)) {
            throw new IOException(format(
                "Could not bind KeyCloak realm %s %s to flow %s", realm, binding, flowAlias));
        }
        return response;
    }

    /** The internal UUID KeyCloak assigns a client, or null when no such client exists. */
    private String clientUuid(String adminToken, String realm, String clientId) throws IOException {
        String uri = format("/admin/realms/%s/clients?clientId=%s",
                            realm, URLEncoder.encode(clientId, StandardCharsets.UTF_8));
        return restClient.get(uri, adminToken, response -> {
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

    private HttpResponse putJson(String adminToken, String uri, String json, String what) throws IOException {
        // Never log the JSON: it can carry secrets, eg a user's password or a client secret.
        logger.info("Calling PUT {} for {}", uri, what);
        return restClient.put(uri, json, adminToken);
    }

    @Override
    public HttpResponse createUser(String adminToken,
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
        String       json     = mapper.writeValueAsString(userObject);
        HttpResponse response = postJson(adminToken, format("/admin/realms/%s/users", realm), json, "user " + user.username());
        int          code     = response.getCode();
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
        return response;
    }

    /**
     * @return true if a user with exactly this username exists in the realm.
     */
    private boolean userExists(String adminToken, String realm, String username) throws IOException {
        String uri = format(
            "/admin/realms/%s/users?exact=true&username=%s",
            realm, URLEncoder.encode(username, StandardCharsets.UTF_8));
        return restClient.get(uri, adminToken, response -> {
            if (response.getCode() != HttpStatus.SC_OK) {
                return false;
            }
            List<?> users = mapper.readValue(response.getEntity().getContent(), List.class);
            return !users.isEmpty();
        });
    }

    private HttpResponse postJson(String adminToken, String uri, String json, String what) throws IOException {
        // Never log the JSON: it can carry secrets, eg a user's password or a client secret.
        logger.info("Calling POST {} for {}", uri, what);
        HttpResponse response = restClient.post(uri, json, adminToken);
        int code = response.getCode();
        if (code == HttpStatus.SC_CREATED) {
            logger.info("Created KeyCloak {}", what);
        } else if (code == HttpStatus.SC_CONFLICT) {
            logger.warn("KeyCloak {} already exists", what);
        } else {
            throw new IOException(format("Could not create KeyCloak %s: HTTP %d", what, code));
        }
        return response;
    }

    /**
     * Convenience check used by callers to wait until KeyCloak is serving requests.
     */
    @Override
    public boolean isReady() {
        try {
            return restClient.get("/realms/master", null, response -> response.getCode() == HttpStatus.SC_OK);
        } catch (IOException e) {
            return false;
        }
    }

}