package health.emrys.afon.security;

import health.emrys.afon.config.AfonUser;
import health.emrys.afon.project.TreProjectStack;
import health.emrys.afon.security.keycloak.KeyCloakProvider;
import org.apache.hc.core5.http.HttpResponse;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
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
     * What registering a browser client does to the redirect URIs that the client already has, as an OAuth2 client has a list of the {@code redirect_uri}s that
     * its authorization requests may have (RFC 6749, 3.1.2), which a provider can read and write back.
     */
    enum RedirectStrategy {
        /** The client has the redirect URIs that are registered, and no other: those of an earlier registration are dropped.  The default. */
        REPLACE,
        /**
         * The client has those that it had, and those that are registered, eg for clouds that share the realm, and so the client, to each keep its Jupyter's callbacks.
         * A provider that cannot read the redirect URIs of a client must throw {@link UnsupportedOperationException} for it.
         */
        MERGE;

        /**
         * @param value {@code replace} or {@code merge}, in any case, or blank or {@code null} for {@link #REPLACE}
         * @throws IllegalArgumentException if it is anything else, as a value that is not known must not fall back to replacing, which is what it may have been set to avoid
         */
        public static RedirectStrategy parse(String value) {
            if (value == null || value.isBlank()) {
                return REPLACE;
            }
            for (RedirectStrategy strategy : values()) {
                if (strategy.name().equalsIgnoreCase(value.trim())) {
                    return strategy;
                }
            }
            throw new IllegalArgumentException(String.format("A redirect strategy should be '%s' or '%s', not '%s'",
                                                             REPLACE.name().toLowerCase(Locale.ROOT), MERGE.name().toLowerCase(Locale.ROOT), value));
        }
    }

    /**
     * Ensures the realm exists and (idempotently) provisions the confidential, browser-flow client that
     * oauth2-proxy and JupyterHub use to log users in.
     *
     * @param clientId the OAUTH2 client ID
     * @param clientSecret the OAUTH2 client secret
     * @param redirectUris the exact callback URLs, e.g. {@link KeyCloakProvider#callbackURIs(String)}
     * @param strategy what to do to the redirect URIs of a client that is already there
     */
    List<HttpResponse> registerBrowserClient(String clientId, String clientSecret, List<String> redirectUris, RedirectStrategy strategy);

    /** As {@link #registerBrowserClient(String, String, List, RedirectStrategy)}, replacing the redirect URIs of a client that is already there. */
    default List<HttpResponse> registerBrowserClient(String clientId, String clientSecret, List<String> redirectUris) {
        return registerBrowserClient(clientId, clientSecret, redirectUris, RedirectStrategy.REPLACE);
    }

    /**
     * Creates a confidential client for the OAuth2 authorization-code (browser) flow: standard flow
     * on, direct grants and service accounts off, with the given redirect URIs whitelisted.  A client that is already there is updated,
     * its redirect URIs by the strategy.
     */
    List<HttpResponse> createBrowserClient(String adminToken,
                              String realm,
                              String clientId,
                              String clientSecret,
                              List<String> redirectUris,
                              RedirectStrategy strategy
    ) throws IOException;

    /** As {@link #createBrowserClient(String, String, String, String, List, RedirectStrategy)}, replacing the redirect URIs of a client that is already there. */
    default List<HttpResponse> createBrowserClient(String adminToken, String realm, String clientId, String clientSecret, List<String> redirectUris)
        throws IOException {
        return createBrowserClient(adminToken, realm, clientId, clientSecret, redirectUris, RedirectStrategy.REPLACE);
    }

    /**
     * Takes off the browser client the redirect URIs that are not among those to keep, eg those of a Jupyter that is no longer deployed, which
     * registering the client with {@link RedirectStrategy#MERGE} leaves on it.  Nothing else of the client changes.
     *
     * @param clientId     the OAUTH2 client ID
     * @param clientSecret the OAUTH2 client secret, which the update needs as registering does
     * @param keep         the redirect URIs that stay, ie the callbacks of the Jupyter of every cloud
     * @param dryRun       whether to only say which would be taken off
     * @return the redirect URIs taken off, or, in a dry run, that would be, none if the client is not there
     * @throws IOException if the client could not be read, or updated
     */
    List<String> purgeBrowserClientRedirectUris(String clientId, String clientSecret, Collection<String> keep, boolean dryRun) throws IOException;

    /**
     * Makes every browser login to this realm present a TOTP second factor, while leaving headless
     * logins able to get a token from a username/password alone.
     */
    List<HttpResponse> configureMfa();

    /** @see #configureMfa() */
    List<HttpResponse> configureMfa(String adminToken, String realm) throws IOException;

    HttpResponse createUser(String adminToken, String realm, AfonUser user) throws IOException;

    /**
     * Makes a member in the central identity, once, with no password: they are sent an invitation email whose required
     * actions are to verify the email, set a password and enrol a second factor.  Idempotent: an existing user is left as
     * they are.  The user's token names them by the immutable id returned, never by their email.
     *
     * @return the user's immutable id
     */
    String ensureUser(String email, String firstName, String lastName);

    /**
     * As {@link #ensureUser(String, String, String)}, with the invitation naming a client of the identity, so that the page that ends the required actions has a
     * link back to its home page, its {@code baseUrl}, eg the TRE's address, for a member who has just set their password.  The invitation is sent without it if the
     * client does not exist, or the identity refuses it.
     *
     * @param clientId the client that the invitation names, or {@code null} for none
     */
    default String ensureUser(String email, String firstName, String lastName, String clientId) {
        return ensureUser(email, firstName, lastName);
    }

    /**
     * @return the immutable id of the user with this email, if there is one
     */
    Optional<String> findUser(String email);

    /**
     * Has the tokens of the client carry the audience, so that a Polaris that requires it, as the Polaris of a Data Provider does of every token, accepts
     * those of the client: the admins' clients, which make its catalogs, as the members' TRE clients carry the audiences of their Data Providers already.
     * Idempotent.
     *
     * @throws IllegalStateException if there is no such client
     */
    void ensureClientAudience(String clientId, String audience);

    /**
     * Has the tokens of a service client, which signs in with its secret and has no second factor to give, say that they have the {@code acr}, so that a
     * Polaris that requires it of every token, as the Polaris of a Data Provider does, accepts those of its own admin client.  Only for a client that no
     * person signs in as: the {@code acr} is then the word of whoever holds its secret, not the proof of a second factor.  Idempotent.
     *
     * @throws IllegalStateException if there is no such client
     */
    void ensureClientAcr(String clientId, String acr);

    /**
     * Sends the invitation to an existing user again: the email whose required actions are to verify the email, set a password and enrol a
     * second factor, as {@link #ensureUser} sends it to a new one, and only then.  For a user who never got it, or let it expire, or lost their
     * second factor.  Unlike the invitation of {@code ensureUser}, a failure to send it is an error.
     *
     * @throws IllegalStateException if there is no user with this email, or the email cannot be sent, eg for want of an SMTP server
     */
    void invite(String email);

    /**
     * As {@link #invite(String)}, with the invitation naming a client, as {@link #ensureUser(String, String, String, String)} does.
     *
     * @param clientId the client that the invitation names, or {@code null} for none
     */
    default void invite(String email, String clientId) {
        invite(email);
    }

    /**
     * Removes the user's authenticators, their one-time password credentials, and ends their sessions, so that they enrol a new one: from an invitation, whose
     * actions include it, or at their next sign in.  For a member who lost the device of their second factor, or whose is to be made again under the same name.
     * Their password and the rest of the account are left as they are.
     *
     * @return the number of authenticators removed, none if the user had none
     * @throws IllegalStateException if there is no user with this email
     */
    int removeSecondFactor(String email);

    /**
     * Makes the project stack's clients, group and token claims in the central identity, converging them if they exist: a client
     * for the TRE, whose tokens carry the audiences of the project stack's Data Provider catalogs, and one for Git, whose tokens carry
     * no Data Provider audience.  Each authenticates with the secret it is given, which is the one the TRE is configured with, and each token
     * names the one project stack it is for, only when the user is a member of the project stack's group.
     *
     * @param secrets the secret of each client, by {@code tre} and {@code git}: the client's own, made for the project stack, not the env's
     */
    void ensureProject(TreProjectStack projectStack, Map<String, String> secrets);

    /**
     * Adds the user to the project stack's group, so that they can get a token for the project stack.
     */
    void addMember(TreProjectStack projectStack, String userId);

    /**
     * @return the members of the project stack's group, by their immutable id, with their email
     */
    Map<String, String> members(TreProjectStack projectStack);

    /**
     * Removes the user from the project stack's group and ends their sessions.  A token already issued lasts until it expires.
     */
    void removeMember(TreProjectStack projectStack, String userId);

    /**
     * Closes the project stack's TRE client, so that no token is issued for it.  A token already issued lasts until it expires.
     */
    void closeProject(TreProjectStack projectStack);

    /**
     * Convenience check used by callers to wait until the provider is serving requests.
     */
    boolean isReady();

}
