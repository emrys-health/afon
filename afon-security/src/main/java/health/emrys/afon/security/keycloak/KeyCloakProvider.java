package health.emrys.afon.security.keycloak;

import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUser;
import health.emrys.afon.project.TreProject;
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

    /** The attribute of a realm that maps an {@code acr} to a level of authentication, as JSON. */
    static final String ACR_LOA_MAP = "acr.loa.map";

    /** Alias of the copied browser flow that this realm binds to, with its OTP step forced on. */
    public static final String BROWSER_MFA_FLOW = "browser-mfa";
    /** Alias of the copied direct-grant flow that only {@link #MFA_CLIENT} binds to, with its OTP step forced on. */
    public static final String DIRECT_GRANT_MFA_FLOW = "direct-grant-mfa";
    /**
     * The public client through which a user, with no client secret, gets a token from their username, password and
     * one-time password, eg from a terminal: its direct grant runs {@link #DIRECT_GRANT_MFA_FLOW}, so it never takes a
     * password alone.
     */
    public static final String MFA_CLIENT = "afon-mfa-cli";

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
    private Smtp smtp;
    /** The config that this runs in, if it is made from one: where a project's domains are, when its file does not give them, see {@link #treRedirectUris}. */
    private AfonPropertyProvider config;
    /**
     * How often, and how long apart, a token request failing with an I/O or server error is tried: up to five minutes, as
     * a KeyCloak starting on a busy cluster has taken three minutes to be ready.
     */
    int  tokenAttempts    = 30;
    long tokenRetryMillis = 10_000;

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
        this(properties, null);
    }

    /**
     * As {@link #KeyCloakProvider(AfonPropertyProvider)}, but reaching KeyCloak at the endpoint, if any, rather than at its OIDC
     * domain name, eg {@code localhost:<port>} of a port-forward to its pod, as its ingress may not admit this machine to its admin API.
     *
     * @param endpoint where KeyCloak is reached, as {@code host:port}, or {@code null} for its OIDC domain name
     */
    public KeyCloakProvider(AfonPropertyProvider properties, String endpoint) {
        this(endpoint != null ? endpoint : properties.getValue(OIDC_DOMAIN_NAME),
                properties.getValue(KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME),
                properties.getValue(KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD),
                properties.getValue(AFON_THEME, DEFAULT_AFON_THEME));
        this.smtp = Smtp.of(properties);
        this.config = properties;
    }

    /**
     * The SMTP server that the realm sends its emails through, eg the invitations of {@link #ensureUser}.
     *
     * @param host     the server
     * @param port     its port
     * @param from     the address that the emails are sent from
     * @param security how the connection is secured
     * @param user     the user that authenticates to it, or {@code null} if none does
     * @param password the password of the user
     */
    public record Smtp(String host, int port, String from, Security security, String user, String password) {

        /** How the connection to an SMTP server is secured. */
        public enum Security {
            STARTTLS, SSL, NONE
        }

        /** Never says the password, as it is not logged. */
        @Override
        public String toString() {
            return "Smtp[host=" + host + ", port=" + port + ", from=" + from + ", security=" + security + ", user=" + user + "]";
        }

        public Smtp {
            if (host == null || host.isBlank() || from == null || from.isBlank()) {
                throw new IllegalArgumentException("An SMTP server needs a host and the address to send from");
            }
        }

        /**
         * @return the server that the properties name, or {@code null} if they name none
         * @throws IllegalArgumentException if they name a host with no address to send from, or a port or security that is not valid
         */
        static Smtp of(AfonPropertyProvider properties) {
            String host = properties.getValue(KEYCLOAK_SMTP_HOST, "").trim();
            if (host.isEmpty()) {
                return null;
            }
            String from = properties.getValue(KEYCLOAK_SMTP_FROM, "").trim();
            if (from.isEmpty()) {
                throw new IllegalArgumentException(KEYCLOAK_SMTP_FROM + " is required with " + KEYCLOAK_SMTP_HOST + ": the address that the emails are sent from");
            }
            String port = properties.getValue(KEYCLOAK_SMTP_PORT, "").trim();
            String security = properties.getValue(KEYCLOAK_SMTP_SECURITY, "").trim();
            String user = properties.getValue(KEYCLOAK_SMTP_USERNAME, "").trim();
            try {
                return new Smtp(host, port.isEmpty() ? 587 : Integer.parseInt(port), from,
                                security.isEmpty() ? Security.STARTTLS : Security.valueOf(security.toUpperCase(Locale.ROOT)),
                                user.isEmpty() ? null : user, properties.getValue(KEYCLOAK_SMTP_PASSWORD, ""));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(KEYCLOAK_SMTP_PORT + " must be a number and " + KEYCLOAK_SMTP_SECURITY + " starttls, ssl or none", e);
            }
        }

        /** @return the settings of a realm's {@code smtpServer} */
        Map<String, String> realmSettings() {
            Map<String, String> settings = new LinkedHashMap<>();
            settings.put("host", host);
            settings.put("port", String.valueOf(port));
            settings.put("from", from);
            settings.put("ssl", String.valueOf(security == Security.SSL));
            settings.put("starttls", String.valueOf(security == Security.STARTTLS));
            if (user != null) {
                settings.put("auth", "true");
                settings.put("user", user);
                settings.put("password", password == null ? "" : password);
            } else {
                settings.put("auth", "false");
            }
            return settings;
        }
    }

    /**
     * Has the realm send its emails through this server, from {@link #createRealm} on.
     *
     * @return this provider
     */
    public KeyCloakProvider withSmtp(Smtp smtp) {
        this.smtp = smtp;
        return this;
    }

    /** The URL of KeyCloak's REST API, for tests. */
    String baseUrl() {
        return restClient.getBaseUrl();
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
     * Obtains an access token for a user, with their second factor, via the direct-access-grant (ROPC) flow of
     * {@link #MFA_CLIENT} against the {@code polaris} realm, which requires the one-time password. No client secret
     * is used: the user's own credentials are all it takes. Not retried, as KeyCloak refuses a one-time password used
     * once already.
     *
     * @param otp the current code of the user's authenticator app
     * @throws IllegalStateException if KeyCloak refuses the credentials, eg a wrong or reused one-time password
     */
    public String userToken(String username, String password, String otp) throws IOException {
        logger.info("Getting token for user '{}' with a one-time password, through {}", username, MFA_CLIENT);
        return polarisRealmToken(Map.of(
            "grant_type", "password",
            "client_id",  MFA_CLIENT,
            "username",   username,
            "password",   password,
            "otp",        otp
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
        return getTokenFor(realm, adminUsername, adminPassword);
    }

    public String getTokenFor(String realm, String username, String password) throws IOException {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException(format(
                "No KeyCloak bootstrap admin credentials to authenticate against realm '%s': set "
                    + "KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME and KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD", realm));
        }
        String path = format("/realms/%s/protocol/openid-connect/token", realm);
        logger.info("Retrieving token from {}", path);
        Map<String, String> form = Map.of(
            "grant_type", "password",
            "client_id", ADMIN_CLI,
            "username", username,
            "password", password
        );
        // A KeyCloak that has just started answers 5xx until it is ready, eg 502 or 503 from the ingress while its pod is
        // not yet ready, or 503 "Bootstrap in progress. Retry in 10 seconds." from KeyCloak itself while its bootstrap admin
        // is created, although its realms already answer.  I/O and server errors are therefore retried, while others, eg 401
        // for bad credentials, fail at once, as they would again.
        for (int attempt = 1; ; attempt++) {
            try {
                return restClient.postForm(path, form, HttpResponses::parseAccessToken);
            } catch (IllegalStateException e) {
                if (e.getMessage() != null && e.getMessage().contains("code: 403")) {
                    throw new IllegalStateException(format(
                        "KeyCloak refused this machine (403 Forbidden) at %s: its admin API only admits the addresses of its allowed CIDRs. "
                            + "Admit this machine with 'afon-cli.sh kube-keycloak-whitelist <cloud> set --caller --stack <stack>'. %s",
                        path, e.getMessage()), e);
                }
                throw e;
            } catch (IOException e) {
                if (attempt >= tokenAttempts) {
                    throw e;
                }
                logger.warn("{} (attempt {}/{}), retrying in {}s", e.getMessage(), attempt, tokenAttempts,
                            tokenRetryMillis / 1000);
                try {
                    Thread.sleep(tokenRetryMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting to retry the token request", interrupted);
                }
            }
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
        if (smtp != null) {
            // without it the realm keeps the server it has, and with none an invitation cannot be sent
            realmConfig.put("smtpServer", smtp.realmSettings());
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
     * Creates a confidential client, eg of the Polaris admin ({@code POLARIS_ID}) or of the {@code USER_ID} service user,
     * that gets its token by the client-credentials flow ({@link #clientToken}), with its own secret and no MFA.
     *
     * <p>It takes no username/password (direct access grants are off). If it did, its id and secret could be presented
     * with <em>any</em> user's username and password, as KeyCloak does not tie a client to a user, and the realm's direct
     * grant skips OTP ({@link #DIRECT_GRANT_NO_OTP_FLOW}): whoever knew the secret could get any user a token without MFA.
     * Upserted, so a client made by an earlier deploy, with direct access grants on, is converged.
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
            "directAccessGrantsEnabled", false,
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
    public List<HttpResponse> registerBrowserClient(String clientId, String clientSecret, List<String> redirectUris, RedirectStrategy strategy) {
        List<HttpResponse> responses = new ArrayList<>();
        try {
            String adminToken = adminToken();
            responses.add(createRealm(adminToken, KEYCLOAK_REALM));
            responses.addAll(createBrowserClient(adminToken, KEYCLOAK_REALM, clientId, clientSecret, redirectUris, strategy));
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
                                                  List<String> redirectUris,
                                                  RedirectStrategy strategy
    ) throws IOException {
        List<HttpResponse> responses = new ArrayList<>();
        String json = browserClientJson(clientId, clientSecret, redirectUris);
        HttpResponse created = postJson(
            adminToken, format("/admin/realms/%s/clients", realm), json, "browser client " + clientId);
        responses.add(created);
        if (created != null && created.getCode() == HttpStatus.SC_CONFLICT) {
            // KeyCloak's DB outlives the cluster, so a client provisioned by an earlier deploy survives
            // with whatever redirect URIs it was created with. Creating-if-absent would leave those
            // stale — the browser then arrives with an unregistered redirect_uri and KeyCloak rejects
            // the login with "Invalid parameter: redirect_uri". Overwrite so the client converges on
            // the URIs this deploy actually uses.
            HttpResponse updated = updateBrowserClient(adminToken, realm, clientId, clientSecret, redirectUris, strategy);
            responses.add(updated);
            if (isFailure(updated)) {
                throw new IOException(format("Could not update the browser client %s of realm %s: %s", clientId, realm,
                                             updated == null ? "it could not be looked up" : describe(updated)));
            }
        }
        // Human logins go through this client; copy their username into the claim Polaris maps on so
        // the captured login token resolves to that user's Polaris principal (e.g. "will").
        responses.add(ensureClientMapper(adminToken, realm, clientId, usernamePrincipalMapper()));
        return responses;
    }

    private String browserClientJson(String clientId, String clientSecret, List<String> redirectUris) throws IOException {
        return mapper.writeValueAsString(Map.ofEntries(
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
    }

    /**
     * Overwrites an existing client's representation, looking up its internal UUID by {@code clientId}
     * (the admin API keys updates on the UUID, not the human-readable client ID).  Its redirect URIs are replaced by the given ones, or, with
     * {@link RedirectStrategy#MERGE}, added to: for clouds that share this KeyCloak's realm, and so the client, to each keep their Jupyter's callbacks.
     */
    private HttpResponse updateBrowserClient(String adminToken, String realm, String clientId, String clientSecret, List<String> redirectUris,
                                             RedirectStrategy strategy)
        throws IOException {
        String uuid = clientUuid(adminToken, realm, clientId);
        if (uuid == null) {
            logger.warn("Browser client {} reported as existing but could not be looked up", clientId);
            return null;
        }
        String uri = format("/admin/realms/%s/clients/%s", realm, uuid);
        List<String> existing = existingRedirectUris(adminToken, uri, clientId);
        List<String> registered = redirectUris;
        if (strategy == RedirectStrategy.MERGE) {
            registered = new ArrayList<>(new LinkedHashSet<>(existing));
            registered.addAll(redirectUris.stream().filter(added -> !existing.contains(added)).toList());
            logger.info("Keeping the redirect URIs of browser client {}, as the strategy is {}: {}", clientId, strategy, registered);
        } else {
            warnIfDropping(clientId, existing, redirectUris);
        }
        HttpResponse response = putJson(adminToken, uri, browserClientJson(clientId, clientSecret, registered), "browser client " + clientId);
        if (isFailure(response)) {
            logger.warn("Could not update browser client {}", clientId);
        } else {
            logger.info("Updated existing KeyCloak browser client {}", clientId);
        }
        return response;
    }

    private static String describe(HttpResponse response) {
        return "HTTP " + response.getCode() + " " + HttpResponses.contentOf(response);
    }

    @Override
    public List<String> purgeBrowserClientRedirectUris(String clientId, String clientSecret, Collection<String> keep, boolean dryRun) throws IOException {
        String adminToken = adminToken();
        String uuid       = clientUuid(adminToken, KEYCLOAK_REALM, clientId);
        if (uuid == null) {
            logger.info("Browser client {} is not in realm {}, so has no redirect URIs to purge", clientId, KEYCLOAK_REALM);
            return List.of();
        }
        String uri = format("/admin/realms/%s/clients/%s", KEYCLOAK_REALM, uuid);
        List<String> existing = readRedirectUris(adminToken, uri);
        List<String> stale    = existing.stream().filter(existingUri -> !keep.contains(existingUri)).toList();
        if (stale.isEmpty() || dryRun) {
            return stale;
        }
        List<String> kept = existing.stream().filter(keep::contains).toList();
        HttpResponse response = putJson(adminToken, uri, browserClientJson(clientId, clientSecret, kept), "browser client " + clientId);
        if (isFailure(response)) {
            throw new IOException(format("Could not purge the redirect URIs of the browser client %s of realm %s: %s", clientId, KEYCLOAK_REALM,
                                         response == null ? "no response" : describe(response)));
        }
        logger.info("Purged the redirect URIs of browser client {}: {}", clientId, stale);
        return stale;
    }

    /** The redirect URIs that the client has, or none if they cannot be read. */
    private List<String> existingRedirectUris(String adminToken, String uri, String clientId) {
        try {
            return readRedirectUris(adminToken, uri);
        } catch (IOException | RuntimeException e) {
            logger.debug("Could not read the redirect URIs of browser client {}: {}", clientId, e.getMessage());
            return List.of();
        }
    }

    /** @throws IOException if the client could not be read, as none would then be taken for there being none */
    private List<String> readRedirectUris(String adminToken, String uri) throws IOException {
        return restClient.get(uri, adminToken, response -> {
            if (response.getCode() != HttpStatus.SC_OK) {
                throw new IOException(format("Could not read the client at %s: HTTP %d", uri, response.getCode()));
            }
            Object found = mapper.readValue(response.getEntity().getContent(), Map.class).get("redirectUris");
            return found instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.<String>of();
        });
    }

    /**
     * Warns of the redirect URIs that overwriting the client takes off it: those of another cloud's Jupyter, if the clouds share this
     * KeyCloak's realm, which then cannot log in any more.  Not an error, as a client left by an earlier deploy has stale ones too.
     */
    private void warnIfDropping(String clientId, List<String> existing, List<String> redirectUris) {
        List<String> dropped = existing.stream().filter(existingUri -> !redirectUris.contains(existingUri)).toList();
        if (!dropped.isEmpty()) {
            logger.warn("Overwriting the redirect URIs of browser client {} drops {}: if they are another cloud's Jupyter, the clouds share this KeyCloak's "
                        + "realm, so only the last set up can log in.  Set {} to merge to keep them", clientId, dropped, OIDC_REDIRECT_STRATEGY);
        }
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
     * <p>The direct grant had its OTP step taken out for headless logins as a human, eg a Spark or Trino job, which has
     * nobody to read a phone. Those now use the client-credentials flow of a confidential client instead
     * ({@link #createConfidentialClient}), which runs no authentication flow, so no client is left that takes a
     * username/password through the realm's direct grant: the second factor guards every login as a user.
     *
     * <p>No public client may take a username/password through the realm's direct grant: KeyCloak's
     * own {@value #ADMIN_CLI} client, which every realm has, is public and takes them by default, so would give any user
     * a token with their password alone. Its direct access grants are therefore turned off in this realm (not in
     * 'master', whose own {@value #ADMIN_CLI} {@link #adminToken()} uses).
     *
     * <p>A user who wants a token from a terminal instead uses {@value #MFA_CLIENT}, a public client whose direct grant
     * alone runs {@value #DIRECT_GRANT_MFA_FLOW}, so takes their username, password <em>and</em> one-time password.
     *
     * <p>KeyCloak refuses to modify its own built-in flows, so each is copied first and the realm
     * bound to the copy. Idempotent: re-running against a converged realm re-copies nothing (the copy
     * 409s) and issues no execution updates.
     */
    @Override
    public List<HttpResponse> configureMfa(String adminToken, String realm) throws IOException {
        List<HttpResponse> responses = new  ArrayList<>();
        responses.addAll(requireOtpForBrowserLogins(adminToken, realm));
//        responses.addAll(skipOtpForDirectGrants(adminToken, realm));
        responses.addAll(disableDirectGrants(adminToken, realm, ADMIN_CLI));
        responses.addAll(requireOtpForMfaClient(adminToken, realm));
        return responses;
    }

    /**
     * Makes {@link #DIRECT_GRANT_MFA_FLOW}, a direct-grant flow whose OTP step is mandatory, and binds {@link #MFA_CLIENT}'s
     * direct grant to it, leaving the realm's own direct grant as it is. The flow is converged before the client is bound,
     * so the client never runs a flow that lets a password alone through.
     */
    private List<HttpResponse> requireOtpForMfaClient(String adminToken, String realm) throws IOException {
        List<HttpResponse> responses = new ArrayList<>();
        responses.add(copyFlow(adminToken, realm, DefaultAuthenticationFlows.DIRECT_GRANT_FLOW, DIRECT_GRANT_MFA_FLOW));
        List<Map<String, Object>> changed =
            KeyCloakMfa.requireDirectGrantOtp(flowExecutions(adminToken, realm, DIRECT_GRANT_MFA_FLOW));
        responses.addAll(updateFlowExecutions(adminToken, realm, DIRECT_GRANT_MFA_FLOW, changed));
        String flowId = flowId(adminToken, realm, DIRECT_GRANT_MFA_FLOW);
        responses.addAll(createMfaClient(adminToken, realm, flowId));
        logger.info(
            "KeyCloak realm '{}' client '{}' takes a password only with a one-time password, by flow '{}' ({} execution(s) updated)",
            realm, MFA_CLIENT, DIRECT_GRANT_MFA_FLOW, changed.size());
        return responses;
    }

    /**
     * Creates, or converges, {@link #MFA_CLIENT}: public, so with no secret, taking only direct grants, which run the flow,
     * and stamping the username onto the claim Polaris maps on, as the browser client does.
     */
    private List<HttpResponse> createMfaClient(String adminToken, String realm, String flowId) throws IOException {
        List<HttpResponse> responses = new ArrayList<>();
        String json = mapper.writeValueAsString(Map.of(
            "clientId", MFA_CLIENT,
            "enabled", true,
            "protocol", "openid-connect",
            "publicClient", true,
            "standardFlowEnabled", false,
            "implicitFlowEnabled", false,
            "serviceAccountsEnabled", false,
            "directAccessGrantsEnabled", true,
            "authenticationFlowBindingOverrides", Map.of("direct_grant", flowId)
        ));
        HttpResponse created = postJson(adminToken, format("/admin/realms/%s/clients", realm), json, "client " + MFA_CLIENT);
        responses.add(created);
        if (created != null && created.getCode() == HttpStatus.SC_CONFLICT) {
            String uuid = clientUuid(adminToken, realm, MFA_CLIENT);
            HttpResponse updated = uuid == null ? null
                : putJson(adminToken, format("/admin/realms/%s/clients/%s", realm, uuid), json, "client " + MFA_CLIENT);
            if (isFailure(updated)) {
                throw new IOException(format("Could not update KeyCloak client %s of realm %s", MFA_CLIENT, realm));
            }
            responses.add(updated);
        } else if (isFailure(created)) {
            throw new IOException(format("Could not create KeyCloak client %s of realm %s", MFA_CLIENT, realm));
        }
        responses.add(ensureClientMapper(adminToken, realm, MFA_CLIENT, usernamePrincipalMapper()));
        return responses;
    }

    /** The id KeyCloak gave the realm's flow of this alias, which a client's flow binding names it by. */
    private String flowId(String adminToken, String realm, String flowAlias) throws IOException {
        String uri = format("/admin/realms/%s/authentication/flows", realm);
        List<Map<String, Object>> flows = restClient.get(uri, adminToken, response -> {
            if (response.getCode() != HttpStatus.SC_OK) {
                throw new IOException(format("Could not list KeyCloak flows of realm %s: HTTP %d", realm, response.getCode()));
            }
            return mapper.readValue(response.getEntity().getContent(), List.class);
        });
        return flows.stream()
                    .filter(flow -> flowAlias.equals(flow.get("alias")))
                    .map(flow -> (String) flow.get("id"))
                    .findFirst()
                    .orElseThrow(() -> new IOException(format("No KeyCloak flow '%s' in realm %s", flowAlias, realm)));
    }

    /** KeyCloak's own public client, which every realm has. */
    public static final String ADMIN_CLI = "admin-cli";

    /**
     * Stops the client taking a username/password for a token, if it does.
     *
     * @return the response of the update, or none if the client is absent or already takes none
     */
    private List<HttpResponse> disableDirectGrants(String adminToken, String realm, String clientId) throws IOException {
        String uuid = clientUuid(adminToken, realm, clientId);
        if (uuid == null) {
            logger.info("No KeyCloak client {} in realm {} whose direct access grants to disable", clientId, realm);
            return List.of();
        }
        String uri = format("/admin/realms/%s/clients/%s", realm, uuid);
        Map<String, Object> client = restClient.get(uri, adminToken, response -> {
            if (response.getCode() != HttpStatus.SC_OK) {
                throw new IOException(format(
                    "Could not read KeyCloak client %s of realm %s: HTTP %d", clientId, realm, response.getCode()));
            }
            return mapper.readValue(response.getEntity().getContent(), Map.class);
        });
        if (Boolean.FALSE.equals(client.get("directAccessGrantsEnabled"))) {
            logger.info("KeyCloak client {} of realm {} already takes no username/password", clientId, realm);
            return List.of();
        }
        client.put("directAccessGrantsEnabled", false);
        HttpResponse response = putJson(adminToken, uri, mapper.writeValueAsString(client), "client " + clientId);
        if (isFailure(response)) {
            throw new IOException(format(
                "Could not disable direct access grants of KeyCloak client %s of realm %s", clientId, realm));
        }
        logger.info("KeyCloak client {} of realm {} no longer takes a username/password", clientId, realm);
        return List.of(response);
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

    public boolean deleteClient(String clientId) {
        try {
            return deleteClient(adminToken(), KEYCLOAK_REALM, clientId);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete KeyCloak client " + clientId, e);
        }
    }

    /**
     * Deletes a client, looking up its internal UUID by {@code clientId} (the admin API keys deletes
     * on the UUID, not the human-readable client ID).
     *
     * @return true if the client was deleted, false if there was no such client
     * @throws IOException if KeyCloak refuses the delete
     */
    public boolean deleteClient(String adminToken, String realm, String clientId) throws IOException {
        String uuid = clientUuid(adminToken, realm, clientId);
        if (uuid == null) {
            logger.info("No KeyCloak client {} in realm {} to delete", clientId, realm);
            return false;
        }
        String uri = format("/admin/realms/%s/clients/%s", realm, uuid);
        logger.info("Calling DELETE {} for client {}", uri, clientId);
        HttpResponse response = restClient.delete(uri, adminToken);
        if (response.getCode() == HttpStatus.SC_NOT_FOUND) {
            // Deleted by someone else between the lookup and the delete.
            logger.info("KeyCloak client {} in realm {} was already deleted", clientId, realm);
            return false;
        }
        if (isFailure(response)) {
            throw new IOException(format("Could not delete KeyCloak client '%s' in realm '%s': HTTP %d",
                                         clientId, realm, response.getCode()));
        }
        logger.info("Deleted KeyCloak client {} from realm {}", clientId, realm);
        return true;
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
        if (code == HttpStatus.SC_CONFLICT) {
            // the email is the identity: the user who holds it is the user, whatever their username, eg a member that was made, by their
            // email, before the setup of the TRE made this admin
            String id = user.email() == null || user.email().isBlank() ? null : userIdByEmail(adminToken, realm, user.email());
            if (id == null) {
                id = userId(adminToken, realm, user.username());
            }
            if (id == null) {
                throw new IOException(format(
                    "Could not create KeyCloak user '%s' in realm '%s': HTTP 409 but no user with that "
                    + "username exists. The email '%s' is most likely already held by another user "
                    + "(realm duplicateEmailsAllowed=false).", user.username(), realm, user.email()));
            }
            // The POST leaves the credentials of an existing user as they were, eg those of an earlier setup, as the
            // KeyCloak database outlives the stacks: users.csv is the source of truth, as it is for the clients' secrets.
            resetPassword(adminToken, realm, id, user);
        }
        return response;
    }

    /**
     * Sets the password of an existing user, which is not temporary, as {@link #createUser} does for a new one.
     */
    private void resetPassword(String adminToken, String realm, String userId, AfonUser user) throws IOException {
        String json = mapper.writeValueAsString(Map.of("type", "password", "value", user.password(), "temporary", false));
        HttpResponse reset = putJson(adminToken, format("/admin/realms/%s/users/%s/reset-password", realm, userId),
                                     json, "the password of user " + user.username());
        if (isFailure(reset)) {
            throw new IOException(format("Could not set the password of the existing KeyCloak user '%s' in realm '%s': HTTP %d",
                                         user.username(), realm, reset.getCode()));
        }
        logger.info("Set the password of the existing KeyCloak user {}", user.username());
    }

    /**
     * @return the id of the user with exactly this username in the realm, or null if there is none.
     */
    private String userId(String adminToken, String realm, String username) throws IOException {
        return userIdBy(adminToken, realm, "username", username);
    }

    /**
     * @return the id of the user with exactly this email in the realm, or null if there is none: a user whose username is not their email,
     * as an admin's of {@code users.csv} may not be
     */
    private String userIdByEmail(String adminToken, String realm, String email) throws IOException {
        return userIdBy(adminToken, realm, "email", email);
    }

    private String userIdBy(String adminToken, String realm, String field, String value) throws IOException {
        String uri = format(
            "/admin/realms/%s/users?exact=true&%s=%s",
            realm, field, URLEncoder.encode(value, StandardCharsets.UTF_8));
        return restClient.get(uri, adminToken, response -> {
            if (response.getCode() != HttpStatus.SC_OK) {
                return null;
            }
            List<?> users = mapper.readValue(response.getEntity().getContent(), List.class);
            return users.isEmpty() ? null : (String) ((Map<?, ?>) users.get(0)).get("id");
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

    // ---- Projects: a client per project, and a group ----

    /** The Gitea provider name that its OIDC callback path is under, {@code /user/oauth2/<name>/callback}. */
    static final String GITEA_AUTH_NAME = "keycloak";
    /** The user attribute holding the principal name that the user's tokens carry, {@code afon:<user id>}. */
    static final String PRINCIPAL_ATTRIBUTE = "polaris_principal";
    /** The prefix of a principal made of an external user, eg {@code afon:<user id>}, as the Data Providers name it. */
    public static final String PRINCIPAL_PREFIX = "afon:";
    private static final List<String> INVITATION_ACTIONS = List.of("VERIFY_EMAIL", "UPDATE_PASSWORD", "CONFIGURE_TOTP");
    private static final String AUDIENCE_MAPPER_PREFIX = "aud-";

    /**
     * The exact redirect URIs of the project's TRE client: JupyterHub's and the oauth2-proxy gate's, which VS Code and
     * the remote desktop are served under.  The domain is the one that the project's file gives, else the one that its config has, which is derived
     * from the env's (see {@code ProjectNames}), as a project's TRE has its domains derived unless the file names them.
     *
     * @param config the config that the project runs in, or {@code null} if none
     */
    static List<String> treRedirectUris(TreProject project, AfonPropertyProvider config) {
        return callbackURIs(treDomain(project, config));
    }

    /**
     * @return the Jupyter domain of the project's TRE: the one that the project's file gives, else the one that its config has
     */
    static String treDomain(TreProject project, AfonPropertyProvider config) {
        String property = project.cloud().name() + "_JUPYTER_DOMAIN_NAME";
        String domain = project.tre().domains().get(property);
        if ((domain == null || domain.isBlank()) && config != null) {
            domain = config.getProperties().getProperty(property);
        }
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException(format(
                "%s is neither in stacks.tre.domains of the project's file nor in its config, which the redirect URIs of the project's TRE client are made from: "
                + "configure the project, and run this in its home", property));
        }
        return domain.trim();
    }

    /**
     * The exact redirect URI of the project's Git client: Gitea's OIDC callback.
     */
    static List<String> gitRedirectUris(TreProject project) {
        return List.of(format("https://%s/user/oauth2/%s/callback", project.git().domain(), GITEA_AUTH_NAME));
    }

    @Override
    public String ensureUser(String email, String firstName, String lastName) {
        return ensureUser(email, firstName, lastName, null);
    }

    @Override
    public String ensureUser(String email, String firstName, String lastName, String clientId) {
        String username = email.toLowerCase(Locale.ROOT);
        try {
            String token = adminToken();
            ensurePrincipalInUserProfile(token);
            // the email is the identity: a user of the email may have another username, eg an admin of users.csv, and a second one with the
            // same email could not be made
            String id = userIdByEmail(token, KEYCLOAK_REALM, username);
            if (id == null) {
                id = userId(token, KEYCLOAK_REALM, username);
            }
            boolean created = id == null;
            if (created) {
                Map<String, Object> user = new LinkedHashMap<>();
                user.put("username", username);
                user.put("email", username);
                user.put("enabled", true);
                user.put("emailVerified", false);
                if (firstName != null && !firstName.isBlank()) {
                    user.put("firstName", firstName);
                }
                if (lastName != null && !lastName.isBlank()) {
                    user.put("lastName", lastName);
                }
                user.put("requiredActions", INVITATION_ACTIONS);
                postJson(token, format("/admin/realms/%s/users", KEYCLOAK_REALM), mapper.writeValueAsString(user), "user " + username);
                id = userId(token, KEYCLOAK_REALM, username);
                if (id == null) {
                    throw new IOException(format("KeyCloak made user '%s' but does not list them", username));
                }
            }
            setPrincipalAttribute(token, id);
            if (created) {
                invite(token, id, username, clientId);
            }
            return id;
        } catch (IOException e) {
            throw new RuntimeException("Failed to make KeyCloak user " + username, e);
        }
    }

    /**
     * Declares {@link #PRINCIPAL_ATTRIBUTE} in the realm's user profile, readable and writable by an admin only, so that a user cannot
     * set their own principal.  KeyCloak 24 and later silently drops an attribute that the user profile does not declare, so without
     * this the user would have no principal, and no token would carry the claim.  Idempotent.
     */
    private void ensurePrincipalInUserProfile(String token) throws IOException {
        String uri = format("/admin/realms/%s/users/profile", KEYCLOAK_REALM);
        Map<String, Object> profile = getMap(token, uri, "the user profile");
        List<Map<String, Object>> attributes = new ArrayList<>();
        Object existing = profile.get("attributes");
        if (existing instanceof List<?> list) {
            for (Object attribute : list) {
                if (attribute instanceof Map<?, ?> map) {
                    if (PRINCIPAL_ATTRIBUTE.equals(map.get("name"))) {
                        return;
                    }
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((key, value) -> copy.put(String.valueOf(key), value));
                    attributes.add(copy);
                }
            }
        }
        attributes.add(new LinkedHashMap<>(Map.of(
            "name", PRINCIPAL_ATTRIBUTE,
            "multivalued", false,
            "permissions", Map.of("view", List.of("admin"), "edit", List.of("admin")))));
        profile.put("attributes", attributes);
        if (isFailure(putJson(token, uri, mapper.writeValueAsString(profile), "the user profile"))) {
            throw new IOException("Could not declare " + PRINCIPAL_ATTRIBUTE + " in the user profile of realm " + KEYCLOAK_REALM);
        }
        logger.info("Declared {} in the user profile of realm {}", PRINCIPAL_ATTRIBUTE, KEYCLOAK_REALM);
    }

    /**
     * Gives the user the attribute that a protocol mapper puts in the user claim of their tokens, {@code afon:<user id>},
     * so that Polaris' default principal mapper, which reads the claim as it is, yields the Data Provider's principal name: the
     * immutable id, never a username, which can change or be reused.
     */
    private void setPrincipalAttribute(String token, String id) throws IOException {
        String uri = format("/admin/realms/%s/users/%s", KEYCLOAK_REALM, id);
        Map<String, Object> user = getMap(token, uri, "user " + id);
        Map<String, Object> attributes = new LinkedHashMap<>();
        Object existing = user.get("attributes");
        if (existing instanceof Map<?, ?> map) {
            map.forEach((key, value) -> attributes.put(String.valueOf(key), value));
        }
        List<String> wanted = List.of(PRINCIPAL_PREFIX + id);
        if (wanted.equals(attributes.get(PRINCIPAL_ATTRIBUTE))) {
            return;
        }
        attributes.put(PRINCIPAL_ATTRIBUTE, wanted);
        user.put("attributes", attributes);
        if (isFailure(putJson(token, uri, mapper.writeValueAsString(user), "the principal of user " + id))) {
            throw new IOException("Could not set the principal attribute of KeyCloak user " + id);
        }
    }

    /**
     * Sends the invitation email.  It needs SMTP configured on the realm, so that a failure is a warning: the user exists
     * and can be invited again.
     */
    private void invite(String token, String id, String username, String clientId) throws IOException {
        HttpResponse response = sendInvitation(token, id, username, clientId);
        if (isFailure(response)) {
            logger.warn("Could not send the invitation email to {} (HTTP {}): is SMTP configured on the realm?", username,
                        response.getCode());
        }
    }

    /**
     * Sends the invitation, naming the client if there is one that exists, so that the page that ends the required actions links back to its {@code baseUrl}.
     * Only {@code client_id} is given, and no {@code redirect_uri}, which would have to be one that the client registers: the identity refuses the call otherwise.
     * If it refuses the call with the client, eg because the client is disabled, the invitation is sent without it, as it must still be sent.
     */
    private HttpResponse sendInvitation(String token, String id, String username, String clientId) throws IOException {
        String uri = format("/admin/realms/%s/users/%s/execute-actions-email", KEYCLOAK_REALM, id);
        String body = mapper.writeValueAsString(INVITATION_ACTIONS);
        String what = "the invitation of " + username;
        if (clientId != null && !clientId.isBlank() && clientUuid(token, KEYCLOAK_REALM, clientId) != null) {
            HttpResponse response = putJson(token, uri + "?client_id=" + java.net.URLEncoder.encode(clientId, java.nio.charset.StandardCharsets.UTF_8), body, what);
            if (!isFailure(response)) {
                return response;
            }
            logger.warn("The identity refused the invitation of {} naming the client {} (HTTP {}): sending it without", username, clientId, response.getCode());
        }
        return putJson(token, uri, body, what);
    }

    @Override
    public int removeSecondFactor(String email) {
        String address = email.toLowerCase(Locale.ROOT);
        try {
            String token = adminToken();
            String id = userIdOf(token, address);
            if (id == null) {
                throw new IllegalStateException("No user " + address + " in the central identity");
            }
            int removed = 0;
            for (Map<String, Object> credential : getList(token, format("/admin/realms/%s/users/%s/credentials", KEYCLOAK_REALM, id),
                                                           "the credentials of " + address)) {
                if ("otp".equals(credential.get("type"))) {
                    HttpResponse response = restClient.delete(format("/admin/realms/%s/users/%s/credentials/%s", KEYCLOAK_REALM, id, credential.get("id")), token);
                    if (isFailure(response)) {
                        throw new IOException(format("Could not remove the authenticator %s of %s: HTTP %d", credential.get("id"), address, response.getCode()));
                    }
                    removed++;
                }
            }
            if (removed > 0) {
                // a session that passed the second factor with the device that is gone is ended, as it is for a member removed from a project
                HttpResponse logout = restClient.post(format("/admin/realms/%s/users/%s/logout", KEYCLOAK_REALM, id), "", token);
                if (isFailure(logout)) {
                    throw new IOException(format("Could not end the sessions of %s: HTTP %d", address, logout.getCode()));
                }
            }
            return removed;
        } catch (IOException e) {
            throw new RuntimeException("Failed to remove the authenticators of KeyCloak user " + address, e);
        }
    }

    @Override
    public Optional<String> findUser(String email) {
        try {
            return Optional.ofNullable(userIdOf(adminToken(), email.toLowerCase(Locale.ROOT)));
        } catch (IOException e) {
            throw new RuntimeException("Failed to look up KeyCloak user " + email, e);
        }
    }

    /**
     * @return the id of the user of the email, which is their identity, whatever their username is, else of the one with it as their username, or null
     */
    private String userIdOf(String token, String email) throws IOException {
        String id = userIdByEmail(token, KEYCLOAK_REALM, email);
        return id != null ? id : userId(token, KEYCLOAK_REALM, email);
    }

    @Override
    public void ensureClientAudience(String clientId, String audience) {
        try {
            String token = adminToken();
            String uuid = clientUuid(token, KEYCLOAK_REALM, clientId);
            if (uuid == null) {
                throw new IllegalStateException("No client " + clientId + " in the central identity to give the audience " + audience);
            }
            upsertMapper(token, KEYCLOAK_REALM, uuid, Map.of(
                "name", AUDIENCE_MAPPER_PREFIX + audience,
                "protocol", "openid-connect",
                "protocolMapper", "oidc-audience-mapper",
                "config", Map.of(
                    "included.custom.audience", audience,
                    "access.token.claim", "true",
                    "id.token.claim", "false",
                    "introspection.token.claim", "true")));
        } catch (IOException e) {
            throw new RuntimeException("Failed to give the client " + clientId + " the audience " + audience, e);
        }
    }

    @Override
    public void ensureClientAcr(String clientId, String acr) {
        try {
            String token = adminToken();
            String uuid = clientUuid(token, KEYCLOAK_REALM, clientId);
            if (uuid == null) {
                throw new IllegalStateException("No client " + clientId + " in the central identity to give the acr " + acr);
            }
            // the realm's own mapper, of the client scope of the acr, says the level of the login, which is the first for a client that signs in with a secret
            removeDefaultScope(token, KEYCLOAK_REALM, uuid, "acr");
            upsertMapper(token, KEYCLOAK_REALM, uuid, Map.of(
                "name", "acr-of-the-service",
                "protocol", "openid-connect",
                "protocolMapper", "oidc-hardcoded-claim-mapper",
                "config", Map.of(
                    "claim.name", "acr",
                    "claim.value", acr,
                    "jsonType.label", "String",
                    "access.token.claim", "true",
                    "id.token.claim", "false",
                    "introspection.token.claim", "true")));
        } catch (IOException e) {
            throw new RuntimeException("Failed to give the client " + clientId + " the acr " + acr, e);
        }
    }

    @Override
    public void invite(String email) {
        invite(email, null);
    }

    @Override
    public void invite(String email, String clientId) {
        String address = email.toLowerCase(Locale.ROOT);
        try {
            String token = adminToken();
            String id = userIdOf(token, address);
            if (id == null) {
                throw new IllegalStateException("No user " + address + " in the central identity: add them to a project first");
            }
            HttpResponse response = sendInvitation(token, id, address, clientId);
            if (isFailure(response)) {
                throw new IllegalStateException(format(
                    "Could not send the invitation email to %s (HTTP %d): is SMTP configured on the realm? See KEYCLOAK_SMTP_HOST", address, response.getCode()));
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to invite KeyCloak user " + address, e);
        }
    }

    @Override
    public void ensureProject(TreProject project, Map<String, String> secrets) {
        String id = project.id().value();
        TreProject.Identity identity = project.identity();
        try {
            String token = adminToken();
            String realm = KEYCLOAK_REALM;
            ensurePrincipalInUserProfile(token);
            ensureAcrLevel(token, realm, identity.acr());
            String treDomain = treDomain(project, config);
            String tre = ensureProjectClient(token, realm, identity.treClient(), callbackURIs(treDomain), "https://" + treDomain + "/", secretOf(secrets, "tre"),
                                             ensureBrowserFlow(token, realm, identity.treClient(), id), identity.acr());
            // a project with no Git of its own has Git deployed with its TRE, so no Git client
            String git = project.git() == null ? null
                : ensureProjectClient(token, realm, identity.gitClient(), gitRedirectUris(project), "https://" + project.git().domain() + "/", secretOf(secrets, "git"),
                                      ensureBrowserFlow(token, realm, identity.gitClient(), id), identity.acr());
            List<String> treAudiences = new ArrayList<>(List.of(identity.treClient()));
            project.dataProviders().forEach(provider -> treAudiences.add(provider.audience()));
            convergeProjectClient(token, realm, tre, identity.treClient(), id, identity, treAudiences);
            if (git != null) {
                convergeProjectClient(token, realm, git, identity.gitClient(), id, identity, List.of(identity.gitClient()));
            }
            String group = ensureGroup(token, realm, id);
            grantRole(token, realm, group, tre, id);
            if (git != null) {
                grantRole(token, realm, group, git, id);
            }
            logger.info("KeyCloak project {} has client {}{}, and a group of the same name as the project", id, identity.treClient(),
                        git == null ? "" : " and " + identity.gitClient());
        } catch (IOException e) {
            throw new RuntimeException("Failed to make KeyCloak project " + id, e);
        }
    }

    /**
     * @return the secret that a client authenticates with: the one given, which the TRE is given too
     */
    private static String secretOf(Map<String, String> secrets, String client) {
        String secret = secrets.get(client);
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("No secret for the " + client + " client");
        }
        if (secret.strip().length() > MAX_CLIENT_SECRET_LENGTH) {
            throw new IllegalArgumentException(format("The secret for the %s client is %d characters, and Keycloak keeps at most %d: make it with project-secret",
                                                      client, secret.strip().length(), MAX_CLIENT_SECRET_LENGTH));
        }
        return secret.strip();
    }

    /** The most that Keycloak's database holds of a client's secret: a longer one is a server error, not a refusal. */
    private static final int MAX_CLIENT_SECRET_LENGTH = 255;

    /**
     * Makes, or converges, a project's client: standard flow only (no direct grants, service accounts or implicit flow), with
     * PKCE allowed and not required, full scope off so that no role but its own reaches a token, and authenticating with the secret that it is given.  Its login
     * is the flow that only the project's members pass, after a second factor, see {@link KeyCloakProjectFlow}.
     *
     * @return the client's internal UUID
     */
    private String ensureProjectClient(String token, String realm, String clientId, List<String> redirectUris, String baseUrl, String secret,
                                       String browserFlowId, String acr) throws IOException {
        String json = mapper.writeValueAsString(Map.ofEntries(
            Map.entry("clientId", clientId),
            Map.entry("enabled", true),
            Map.entry("protocol", "openid-connect"),
            Map.entry("publicClient", false),
            Map.entry("clientAuthenticatorType", "client-secret"),
            Map.entry("secret", secret),
            Map.entry("standardFlowEnabled", true),
            Map.entry("directAccessGrantsEnabled", false),
            Map.entry("serviceAccountsEnabled", false),
            Map.entry("implicitFlowEnabled", false),
            Map.entry("fullScopeAllowed", false),
            Map.entry("redirectUris", redirectUris),
            // the home page of the client, which the page that ends an invitation links back to, for a member who has just set their password
            Map.entry("baseUrl", baseUrl),
            Map.entry("webOrigins", List.of("+")),
            // the login that admits only the members, after the second factor
            Map.entry("authenticationFlowBindingOverrides", Map.of("browser", browserFlowId)),
            Map.entry("attributes", Map.of(
                // PKCE is asked for by the clients that can send it, not required: JupyterHub's authenticator, in the version that Afon runs, sends no
                // code_challenge, and a client that requires one would refuse its login.  Empty, so that a client made before as requiring it is changed
                "pkce.code.challenge.method", "",
                // asked for by default, and the least that a login may be: a request for less (acr_values=1) is a request for a token
                // of a password alone, which carries the project claim and the audiences all the same
                "default.acr.values", acr,
                "minimum.acr.value", acr))));
        HttpResponse created = postJson(token, format("/admin/realms/%s/clients", realm), json, "project client " + clientId);
        String uuid = clientUuid(token, realm, clientId);
        if (uuid == null) {
            throw new IOException(format("KeyCloak made client %s but does not list it", clientId));
        }
        if (created.getCode() == HttpStatus.SC_CONFLICT
            && isFailure(putJson(token, format("/admin/realms/%s/clients/%s", realm, uuid), json, "project client " + clientId))) {
            throw new IOException("Could not update KeyCloak project client " + clientId);
        }
        return uuid;
    }

    /**
     * Gives the client the role that marks a member of the project, and the mappers of its tokens' claims.  The role is named
     * by the project's id, so that the mapper, which puts the names of the roles in a claim, puts the project's id in it.
     * The mapper is limited to the client's own roles, so a token of this client names this project and no other, even for
     * a user on several projects, and a token of a non-member lacks the claim, whatever grant issued it.  The {@code roles}
     * scope, which would add other clients' audiences, is taken off.
     */
    private void convergeProjectClient(String token, String realm, String uuid, String clientId, String projectId,
                                       TreProject.Identity identity, List<String> audiences) throws IOException {
        ensureRole(token, realm, uuid, projectId);
        removeDefaultScope(token, realm, uuid, "roles");
        upsertMapper(token, realm, uuid, Map.of(
            "name", "project",
            "protocol", "openid-connect",
            "protocolMapper", "oidc-usermodel-client-role-mapper",
            "config", Map.of(
                "usermodel.clientRoleMapping.clientId", clientId,
                "claim.name", identity.projectClaim(),
                "multivalued", "true",
                "jsonType.label", "String",
                "access.token.claim", "true",
                "id.token.claim", "false",
                "userinfo.token.claim", "false")));
        upsertMapper(token, realm, uuid, Map.of(
            "name", identity.userClaim(),
            "protocol", "openid-connect",
            "protocolMapper", "oidc-usermodel-attribute-mapper",
            "config", Map.of(
                "user.attribute", PRINCIPAL_ATTRIBUTE,
                "claim.name", identity.userClaim(),
                "jsonType.label", "String",
                "access.token.claim", "true",
                "id.token.claim", "false",
                "userinfo.token.claim", "false")));
        Set<String> wanted = new LinkedHashSet<>();
        for (String audience : audiences) {
            String name = AUDIENCE_MAPPER_PREFIX + audience;
            wanted.add(name);
            upsertMapper(token, realm, uuid, Map.of(
                "name", name,
                "protocol", "openid-connect",
                "protocolMapper", "oidc-audience-mapper",
                "config", Map.of(
                    "included.custom.audience", audience,
                    "access.token.claim", "true",
                    "id.token.claim", "false",
                    "introspection.token.claim", "true")));
        }
        // a Data Provider no longer in the project loses its audience
        for (Map<String, Object> existing : projectMappers(token, realm, uuid)) {
            String name = String.valueOf(existing.get("name"));
            if (name.startsWith(AUDIENCE_MAPPER_PREFIX) && !wanted.contains(name)) {
                restClient.delete(format("/admin/realms/%s/clients/%s/protocol-mappers/models/%s", realm, uuid, existing.get("id")), token);
                logger.info("Removed the audience mapper {} of client {}", name, clientId);
            }
        }
    }

    /**
     * Makes the realm map the {@code acr} to the level of authentication that a second factor gives, so that a login at that
     * level has the {@code acr} in its token, and the client that asks for it by default.  The other entries of the map are kept,
     * as the realm's attributes are replaced by those sent.
     */
    private void ensureAcrLevel(String token, String realm, String acr) throws IOException {
        String uri = format("/admin/realms/%s", realm);
        Map<String, Object> representation = getMap(token, uri, "realm " + realm);
        Map<String, Object> attributes = new LinkedHashMap<>();
        Object existing = representation.get("attributes");
        if (existing instanceof Map<?, ?> map) {
            map.forEach((key, value) -> attributes.put(String.valueOf(key), value));
        }
        Map<String, Object> levels = new LinkedHashMap<>();
        Object mapped = attributes.get(ACR_LOA_MAP);
        if (mapped instanceof String json && !json.isBlank()) {
            mapper.readTree(json).fields().forEachRemaining(entry -> levels.put(entry.getKey(), entry.getValue().asText()));
        }
        if (KeyCloakProjectFlow.MFA_LEVEL.equals(levels.get(acr))) {
            return;
        }
        levels.put(acr, KeyCloakProjectFlow.MFA_LEVEL);
        attributes.put(ACR_LOA_MAP, mapper.writeValueAsString(levels));
        if (isFailure(putJson(token, uri, mapper.writeValueAsString(Map.of("attributes", attributes)), "the ACR levels of realm " + realm))) {
            throw new IOException("Could not map the acr " + acr + " to a level in realm " + realm);
        }
        logger.info("Mapped the acr {} to level {} in realm {}", acr, KeyCloakProjectFlow.MFA_LEVEL, realm);
    }

    /**
     * Makes, or converges, the browser login of a project's client, {@link KeyCloakProjectFlow}: what is absent is made, and each
     * requirement and configuration is set as it should be, so that a login that was made is never left open while it is converged.
     *
     * @return the id of the top flow, which the client binds its login to
     */
    private String ensureBrowserFlow(String token, String realm, String clientId, String projectId) throws IOException {
        KeyCloakProjectFlow.Node top = KeyCloakProjectFlow.of(clientId, projectId);
        if (flowIdOrNull(token, realm, top.name()) == null) {
            String json = mapper.writeValueAsString(Map.of("alias", top.name(), "providerId", "basic-flow", "topLevel", true, "builtIn", false,
                                                          "description", "The login of the project's client " + clientId));
            if (isFailure(postJson(token, format("/admin/realms/%s/authentication/flows", realm), json, "flow " + top.name()))) {
                throw new IOException("Could not make KeyCloak flow " + top.name());
            }
        }
        ensureFlowChildren(token, realm, top);
        return flowId(token, realm, top.name());
    }

    private void ensureFlowChildren(String token, String realm, KeyCloakProjectFlow.Node flow) throws IOException {
        for (KeyCloakProjectFlow.Node child : flow.children()) {
            Map<String, Object> execution = directExecution(flowExecutions(token, realm, flow.name()), child);
            if (execution == null) {
                String kind = child.isFlow() ? "flow" : "execution";
                Map<String, Object> body = child.isFlow()
                    ? Map.of("alias", child.name(), "type", "basic-flow", "provider", "registration-page-form", "description", child.name())
                    : Map.of("provider", child.provider());
                String uri = format("/admin/realms/%s/authentication/flows/%s/executions/%s", realm, pathSegment(flow.name()), kind);
                if (isFailure(postJson(token, uri, mapper.writeValueAsString(body), kind + " " + (child.isFlow() ? child.name() : child.provider())))) {
                    throw new IOException(format("Could not make %s %s in KeyCloak flow %s", kind, child.isFlow() ? child.name() : child.provider(), flow.name()));
                }
                execution = directExecution(flowExecutions(token, realm, flow.name()), child);
                if (execution == null) {
                    throw new IOException(format("KeyCloak made %s in flow %s but does not list it", child.isFlow() ? child.name() : child.provider(), flow.name()));
                }
            }
            if (!child.requirement().equals(execution.get("requirement"))) {
                execution.put("requirement", child.requirement());
                if (isFailure(putJson(token, format("/admin/realms/%s/authentication/flows/%s/executions", realm, pathSegment(flow.name())),
                                      mapper.writeValueAsString(execution), "execution " + execution.get("displayName") + " -> " + child.requirement()))) {
                    throw new IOException("Could not set KeyCloak flow execution " + execution.get("displayName"));
                }
            }
            if (!child.config().isEmpty()) {
                ensureExecutionConfig(token, realm, String.valueOf(execution.get("id")), (String) execution.get("authenticationConfig"), child);
            }
            if (child.isFlow()) {
                ensureFlowChildren(token, realm, child);
            }
        }
    }

    /**
     * @return the execution that the flow holds directly, of the node's alias if it is a flow, or its provider if it is not, or null
     */
    private static Map<String, Object> directExecution(List<Map<String, Object>> executions, KeyCloakProjectFlow.Node node) {
        for (Map<String, Object> execution : executions) {
            int level = execution.get("level") instanceof Number number ? number.intValue() : 0;
            boolean isFlow = Boolean.TRUE.equals(execution.get("authenticationFlow"));
            if (level == 0 && isFlow == node.isFlow()
                && (node.isFlow() ? node.name().equals(execution.get("displayName")) : node.provider().equals(execution.get("providerId")))) {
                return execution;
            }
        }
        return null;
    }

    private void ensureExecutionConfig(String token, String realm, String executionId, String configId, KeyCloakProjectFlow.Node node)
        throws IOException {
        Map<String, Object> config = new LinkedHashMap<>(Map.of("alias", node.name(), "config", node.config()));
        if (configId == null) {
            String uri = format("/admin/realms/%s/authentication/executions/%s/config", realm, executionId);
            if (isFailure(postJson(token, uri, mapper.writeValueAsString(config), "the configuration " + node.name()))) {
                throw new IOException("Could not configure KeyCloak flow execution " + node.name());
            }
            return;
        }
        config.put("id", configId);
        if (isFailure(putJson(token, format("/admin/realms/%s/authentication/config/%s", realm, configId), mapper.writeValueAsString(config),
                              "the configuration " + node.name()))) {
            throw new IOException("Could not configure KeyCloak flow execution " + node.name());
        }
    }

    /** @return the id of the realm's flow of this alias, or null if there is none */
    private String flowIdOrNull(String token, String realm, String flowAlias) throws IOException {
        try {
            return flowId(token, realm, flowAlias);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("No KeyCloak flow")) {
                return null;
            }
            throw e;
        }
    }

    private List<Map<String, Object>> projectMappers(String token, String realm, String uuid) throws IOException {
        return getList(token, format("/admin/realms/%s/clients/%s/protocol-mappers/models", realm, uuid), "the mappers of client " + uuid);
    }

    /**
     * Makes the mapper, or converges it if one of its name exists, as a mapper POSTed again would be left as it was.
     */
    private void upsertMapper(String token, String realm, String uuid, Map<String, Object> mapper) throws IOException {
        Optional<Map<String, Object>> existing = projectMappers(token, realm, uuid).stream()
                                                                                    .filter(m -> mapper.get("name").equals(m.get("name")))
                                                                                    .findFirst();
        String base = format("/admin/realms/%s/clients/%s/protocol-mappers/models", realm, uuid);
        if (existing.isEmpty()) {
            postJson(token, base, this.mapper.writeValueAsString(mapper), "mapper " + mapper.get("name"));
            return;
        }
        Map<String, Object> converged = new LinkedHashMap<>(mapper);
        converged.put("id", existing.get().get("id"));
        String id = String.valueOf(existing.get().get("id"));
        if (isFailure(putJson(token, base + "/" + id, this.mapper.writeValueAsString(converged), "mapper " + mapper.get("name")))) {
            throw new IOException("Could not update KeyCloak mapper " + mapper.get("name"));
        }
    }

    private void removeDefaultScope(String token, String realm, String uuid, String scopeName) throws IOException {
        List<Map<String, Object>> scopes = getList(token, format("/admin/realms/%s/client-scopes", realm), "the client scopes");
        for (Map<String, Object> scope : scopes) {
            if (scopeName.equals(scope.get("name"))) {
                HttpResponse response = restClient.delete(
                    format("/admin/realms/%s/clients/%s/default-client-scopes/%s", realm, uuid, scope.get("id")), token);
                if (isFailure(response) && response.getCode() != HttpStatus.SC_NOT_FOUND) {
                    throw new IOException(format("Could not take scope %s off KeyCloak client %s: HTTP %d", scopeName, uuid,
                                                 response.getCode()));
                }
            }
        }
    }

    private void ensureRole(String token, String realm, String uuid, String role) throws IOException {
        postJson(token, format("/admin/realms/%s/clients/%s/roles", realm, uuid),
                 mapper.writeValueAsString(Map.of("name", role)), "role " + role);
    }

    private String ensureGroup(String token, String realm, String name) throws IOException {
        String found = groupId(token, realm, name);
        if (found != null) {
            return found;
        }
        postJson(token, format("/admin/realms/%s/groups", realm), mapper.writeValueAsString(Map.of("name", name)), "group " + name);
        found = groupId(token, realm, name);
        if (found == null) {
            throw new IOException(format("KeyCloak made group %s but does not list it", name));
        }
        return found;
    }

    private String groupId(String token, String realm, String name) throws IOException {
        List<Map<String, Object>> groups = getList(token, format("/admin/realms/%s/groups?exact=true&search=%s", realm,
                                                                  URLEncoder.encode(name, StandardCharsets.UTF_8)), "group " + name);
        return groups.stream().filter(group -> name.equals(group.get("name"))).map(group -> (String) group.get("id")).findFirst().orElse(null);
    }

    /** Assigns the group the client's role, which KeyCloak leaves as it is if the group has it. */
    private void grantRole(String token, String realm, String groupId, String clientUuid, String role) throws IOException {
        Map<String, Object> representation = getMap(token, format("/admin/realms/%s/clients/%s/roles/%s", realm, clientUuid, role), "role " + role);
        HttpResponse response = restClient.post(format("/admin/realms/%s/groups/%s/role-mappings/clients/%s", realm, groupId, clientUuid),
                                                mapper.writeValueAsString(List.of(representation)), token);
        if (isFailure(response)) {
            throw new IOException(format("Could not give group %s the role %s: HTTP %d", groupId, role, response.getCode()));
        }
    }

    @Override
    public void addMember(TreProject project, String userId) {
        try {
            String token = adminToken();
            String group = requireGroup(token, project);
            HttpResponse response = putJson(token, format("/admin/realms/%s/users/%s/groups/%s", KEYCLOAK_REALM, userId, group), "",
                                            "membership of " + userId + " in " + project.id());
            if (isFailure(response)) {
                throw new IOException(format("Could not add user %s to group %s: HTTP %d", userId, project.id(), response.getCode()));
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to add a member to project " + project.id(), e);
        }
    }

    @Override
    public Map<String, String> members(TreProject project) {
        try {
            String token = adminToken();
            String group = requireGroup(token, project);
            Map<String, String> members = new LinkedHashMap<>();
            int first = 0;
            int page = 100;
            while (true) {
                List<Map<String, Object>> users = getList(token, format("/admin/realms/%s/groups/%s/members?first=%d&max=%d", KEYCLOAK_REALM, group, first, page),
                                                          "members of group " + project.id());
                users.forEach(user -> members.put(String.valueOf(user.get("id")), String.valueOf(user.get("email"))));
                if (users.size() < page) {
                    return members;
                }
                first += page;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to list the members of project " + project.id(), e);
        }
    }

    @Override
    public void removeMember(TreProject project, String userId) {
        try {
            String token = adminToken();
            String group = requireGroup(token, project);
            HttpResponse response = restClient.delete(format("/admin/realms/%s/users/%s/groups/%s", KEYCLOAK_REALM, userId, group), token);
            if (isFailure(response) && response.getCode() != HttpStatus.SC_NOT_FOUND) {
                throw new IOException(format("Could not remove user %s from group %s: HTTP %d", userId, project.id(), response.getCode()));
            }
            // ends their sessions in the central identity; a token already issued lasts until it expires
            HttpResponse logout = restClient.post(format("/admin/realms/%s/users/%s/logout", KEYCLOAK_REALM, userId), "", token);
            if (isFailure(logout)) {
                throw new IOException(format("Could not end the sessions of user %s: HTTP %d", userId, logout.getCode()));
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to remove a member from project " + project.id(), e);
        }
    }

    private String requireGroup(String token, TreProject project) throws IOException {
        String group = groupId(token, KEYCLOAK_REALM, project.id().value());
        if (group == null) {
            throw new IOException(format("KeyCloak has no group for project %s: make it with manage-identity create", project.id()));
        }
        return group;
    }

    @Override
    public void closeProject(TreProject project) {
        String clientId = project.identity().treClient();
        try {
            String token = adminToken();
            String uuid = clientUuid(token, KEYCLOAK_REALM, clientId);
            if (uuid == null) {
                logger.info("No KeyCloak client {} to close", clientId);
                return;
            }
            String uri = format("/admin/realms/%s/clients/%s", KEYCLOAK_REALM, uuid);
            Map<String, Object> client = getMap(token, uri, "client " + clientId);
            client.put("enabled", false);
            if (isFailure(putJson(token, uri, mapper.writeValueAsString(client), "client " + clientId))) {
                throw new IOException("Could not disable KeyCloak client " + clientId);
            }
            logger.info("Closed KeyCloak client {}: no token is issued for project {}", clientId, project.id());
        } catch (IOException e) {
            throw new RuntimeException("Failed to close project " + project.id(), e);
        }
    }

    private Map<String, Object> getMap(String token, String uri, String what) throws IOException {
        return restClient.get(uri, token, response -> {
            if (response.getCode() != HttpStatus.SC_OK) {
                throw new IOException(format("Could not read KeyCloak %s: HTTP %d", what, response.getCode()));
            }
            return mapper.readValue(response.getEntity().getContent(), LinkedHashMap.class);
        });
    }

    private List<Map<String, Object>> getList(String token, String uri, String what) throws IOException {
        return restClient.get(uri, token, response -> {
            if (response.getCode() != HttpStatus.SC_OK) {
                throw new IOException(format("Could not read KeyCloak %s: HTTP %d", what, response.getCode()));
            }
            return mapper.readValue(response.getEntity().getContent(), List.class);
        });
    }

    /**
     * Convenience check used by callers to wait until KeyCloak is serving requests, which asks for the public discovery metadata of
     * the {@code master} realm, as the setup does, and not for the realm itself: that is restricted, with the admin paths, to the
     * addresses of {@code <CLOUD>_KEYCLOAK_ALLOWED_CIDRS}, so would refuse a caller outside of them, though KeyCloak is serving.
     */
    @Override
    public boolean isReady() {
        try {
            return restClient.get("/realms/master/.well-known/openid-configuration", null, response -> response.getCode() == HttpStatus.SC_OK);
        } catch (IOException e) {
            return false;
        }
    }

}