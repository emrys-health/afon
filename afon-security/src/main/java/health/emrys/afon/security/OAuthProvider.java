package health.emrys.afon.security;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUser;
import org.apache.hc.core5.http.HttpResponse;

import java.io.IOException;
import java.util.List;

public interface OAuthProvider {

    /**
     * Registers the Polaris user with username and password provided, leaving
     * the email unset.
     */
    void registerPolarisUser(String username, String password);

    /**
     * Registers the Polaris user with the configured properties.
     *
     * <p>Registers the browser for the Jupyter domain.
     *
     * <p>Provisions the browser-flow client that oauth2-proxy and JupyterHub share, otherwise clicking
     * "sign in" on the OAuth splash yields "Client not found", and puts that browser flow
     * behind MFA (see {@link #configureMfa()}).
     */
    void registerPolarisUserAndBrowserClient(AfonPropertyProvider properties);

    /**
     * Registers a Polaris user, also setting the user's (verified) email.
     * A verified email is required for logins that go through oauth2-proxy, which rejects id_tokens
     * whose email is unverified. Pass {@code null}/blank to leave the email unset.
     *
     * Ensures the Polaris realm, client and user all exist.
     * Idempotent: anything that already exists is left untouched.
     */
    void registerPolarisUser(AfonUser user);

    /**
     * Obtains an access token for a provisioned Polaris user via the direct-access-grant (ROPC) flow.
     */
    String userToken(String username, String password) throws IOException;

    /**
     * Authenticates against the 'master' realm as the bootstrap admin.
     *
     * @return An access token for the admin REST API.
     */
    String adminToken() throws IOException;

    /**
     * Authenticates against the given realm, eg 'polaris'.
     *
     * @param realm the realm to authenticate against.
     * @return An access token
     */
    String getToken(String realm) throws IOException;

    /**
     * Creates the realm if absent and, either way, converges its token lifespans.
     */
    HttpResponse createRealm(String adminToken, String realm) throws IOException;

    /**
     * Creates a confidential client with direct access grants enabled so that the user's
     * username/password can be exchanged for a token.
     */
    HttpResponse createConfidentialClient(
        String adminToken,
        String realm,
        String clientId,
        String clientSecret
    ) throws IOException;

    /**
     * Registers the browser-flow client for the Jupyter domain using the OAuth2 client configured in properties.
     */
    List<HttpResponse> registerBrowserClient(AfonPropertyProvider properties, String jupyterDomain);

    /**
     * Ensures the realm exists and (idempotently) provisions the confidential, browser-flow client that
     * oauth2-proxy and JupyterHub use to log users in.
     *
     * @param redirectUris the exact callback URLs, e.g.
     *                     {@code https://jupyter.example/oauth2/callback} (oauth2-proxy) and
     *                     {@code https://jupyter.example/hub/oauth_callback} (JupyterHub).
     */
    List<HttpResponse> registerBrowserClient(String clientId, String clientSecret, List<String> redirectUris);

    /**
     * Creates a confidential client for the OAuth2 authorization-code (browser) flow: standard flow
     * on, direct grants and service accounts off, with the given redirect URIs whitelisted.
     */
    List<HttpResponse> createBrowserClient(String adminToken,
                              String realm,
                              String clientId,
                              String clientSecret,
                              List<String> redirectUris
    ) throws IOException;

    /**
     * Makes every browser login to this realm present a TOTP second factor, while leaving headless
     * logins able to get a token from a username/password alone.
     */
    List<HttpResponse> configureMfa();

    /** @see #configureMfa() */
    List<HttpResponse> configureMfa(String adminToken, String realm) throws IOException;

    HttpResponse createUser(String adminToken, String realm, AfonUser user) throws IOException;

    /**
     * Convenience check used by callers to wait until the provider is serving requests.
     */
    boolean isReady();

}
