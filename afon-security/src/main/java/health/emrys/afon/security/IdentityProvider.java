package health.emrys.afon.security;

import health.emrys.afon.config.AfonUser;
import health.emrys.afon.security.keycloak.KeyCloakProvider;
import org.apache.hc.core5.http.HttpResponse;

import java.io.IOException;
import java.util.List;

public interface IdentityProvider {

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
     * Obtains an access token for a confidential client via the client-credentials flow, eg for the
     * Polaris admin client, which is a client but not a user.
     */
    String clientToken(String clientId, String clientSecret) throws IOException;

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
     * Ensures the realm exists and (idempotently) provisions the confidential, browser-flow client that
     * oauth2-proxy and JupyterHub use to log users in.
     *
     * @param clientId the OAUTH2 client ID
     * @param clientSecret the OAUTH2 client secret
     * @param redirectUris the exact callback URLs, e.g. {@link KeyCloakProvider#callbackURIs(String)}
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
