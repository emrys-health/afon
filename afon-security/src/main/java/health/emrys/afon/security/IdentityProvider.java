package health.emrys.afon.security;

import health.emrys.afon.config.AfonUser;
import health.emrys.afon.project.TreProject;
import health.emrys.afon.security.keycloak.KeyCloakProvider;
import org.apache.hc.core5.http.HttpResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
     * Obtains an access token for a confidential client via the client-credentials flow, eg for the
     * Polaris admin client ({@code POLARIS_ID}) or the {@code USER_ID} service user's client, each of
     * which is a client of the same name and secret, and gets its token with no MFA.
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
     * Makes a researcher in the central identity, once, with no password: they are sent an invitation email whose required
     * actions are to verify the email, set a password and enrol a second factor.  Idempotent: an existing user is left as
     * they are.  The user's token names them by the immutable id returned, never by their email.
     *
     * @return the user's immutable id
     */
    String ensureUser(String email, String firstName, String lastName);

    /**
     * @return the immutable id of the user with this email, if there is one
     */
    Optional<String> findUser(String email);

    /**
     * Sends the invitation to an existing user again: the email whose required actions are to verify the email, set a password and enrol a
     * second factor, as {@link #ensureUser} sends it to a new one, and only then.  For a user who never got it, or let it expire, or lost their
     * second factor.  Unlike the invitation of {@code ensureUser}, a failure to send it is an error.
     *
     * @throws IllegalStateException if there is no user with this email, or the email cannot be sent, eg for want of an SMTP server
     */
    void invite(String email);

    /**
     * Makes the project's clients, group and token claims in the central identity, converging them if they exist: a client
     * for the TRE, whose tokens carry the audiences of the project's Provider catalogs, and one for Git, whose tokens carry
     * no Provider audience.  Each authenticates by {@code private_key_jwt} with the public key it is given, and each token
     * names the one project it is for, only when the user is a member of the project's group.
     *
     * @param publicKeys the public key (a JWK or a JWK set) of each client, by {@code tre} and {@code git}
     */
    void ensureProject(TreProject project, Map<String, String> publicKeys);

    /**
     * Adds the user to the project's group, so that they can get a token for the project.
     */
    void addMember(TreProject project, String userId);

    /**
     * @return the members of the project's group, by their immutable id, with their email
     */
    Map<String, String> members(TreProject project);

    /**
     * Removes the user from the project's group and ends their sessions.  A token already issued lasts until it expires.
     */
    void removeMember(TreProject project, String userId);

    /**
     * Closes the project's TRE client, so that no token is issued for it.  A token already issued lasts until it expires.
     */
    void closeProject(TreProject project);

    /**
     * Convenience check used by callers to wait until the provider is serving requests.
     */
    boolean isReady();

}
