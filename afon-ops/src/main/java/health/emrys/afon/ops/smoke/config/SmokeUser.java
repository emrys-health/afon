package health.emrys.afon.ops.smoke.config;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonCloudProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.ops.project.SmokeMemberCredentials;
import health.emrys.afon.project.FileProjectStackProvider;
import health.emrys.afon.project.ProjectStackEnv;
import health.emrys.afon.project.ProjectStackUserProvider;
import health.emrys.afon.security.keycloak.KeyCloakMemberLogin;
import health.emrys.afon.security.keycloak.KeyCloakProvider;
import health.emrys.afon.spark.connect.SparkConnectClient;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

import static health.emrys.afon.config.AfonProperty.OIDC_AUDIENCE;
import static health.emrys.afon.config.AfonProperty.PROJECT_ID;
import static health.emrys.afon.config.AfonProperty.SMOKE_MEMBER;

/**
 * The user that the smoke tests act as when they test what a user can do: their token, and the name that the services know them by.  It depends on the mode:
 * <ul>
 *   <li>In project mode, the project's smoke member (see {@code manage-identity smoke-credentials}), signed in to the TRE's client as a member is, with the password and the
 *       one-time password of their authenticator, so that the token is a member's, with the project and the second factor in it.  The services know them by the
 *       {@code polaris_principal} claim of the token, {@code afon:<sub>}, and not by their email.</li>
 *   <li>In non-project mode, {@code USER_ID}, an admin, whose token is that of their confidential client in Keycloak, by the client-credentials flow, and whom the services know by
 *       their username.</li>
 * </ul>
 * The token is got when this is made, and lasts as long as Keycloak lets it.
 */
public final class SmokeUser {

    private final String username;
    private final String principal;
    private final String token;
    private final boolean member;

    private SmokeUser(String username, String principal, String token, boolean member) {
        this.username = username;
        this.principal = principal;
        this.token = token;
        this.member = member;
    }

    /** @return their username: the email of the smoke member, or the {@code USER_ID} user's username */
    public String username() {
        return username;
    }

    /** @return the name that Trino, Polaris and their logs know them by: the {@code polaris_principal} of a member's token, else the username */
    public String principal() {
        return principal;
    }

    /** @return their OIDC access token */
    public String token() {
        return token;
    }

    /** @return whether they are a smoke member, as in project mode, and not {@code USER_ID} */
    public boolean isMember() {
        return member;
    }

    /**
     * @return a user that is given, with their token, eg one that signed in by hand, as a test run by hand takes them: not a member, whom the services know by their username
     */
    public static SmokeUser given(String username, String token) {
        return new SmokeUser(username, username, token, false);
    }

    /** What gets the token of an {@code USER_ID} user's confidential client. */
    @FunctionalInterface
    public interface ClientTokens {
        String of(AfonPropertyProvider properties, AfonUser user) throws IOException;
    }

    /**
     * @return the user of the smoke tests in the mode of the config: the smoke member in project mode, else {@code USER_ID}
     * @throws IOException if there is no smoke member, or they have no credentials, or the sign-in or the token is refused
     */
    public static SmokeUser of(AfonPropertyProvider properties) throws IOException, InterruptedException {
        AfonCloud cloud = new SmokeConfig(properties).cloud();
        if (SmokeConfig.isProjectStack(properties)) {
            return ofMember(properties, cloud, ProjectStackEnv.defaultAfonHome(), KeyCloakMemberLogin::new);
        }
        return ofConfiguredUser(properties.forCloud(cloud), ProjectStackUserProvider.forActiveConfig(), SparkConnectClient::userToken);
    }

    /**
     * Signs the smoke member in to the TRE's client, as the TRE issues a token to a member: with the password and the one-time password of the authenticator that
     * {@code smoke-credentials} gave them, which the project stack's secrets dir keeps.
     *
     * @param afonHome the afon home, whose project stack dir has the credentials and the secret of the client
     * @param login    makes the login to a realm, at its issuer URL
     * @throws IOException if there is no smoke member, or they have no credentials, or the sign-in is refused
     */
    public static SmokeUser ofMember(AfonPropertyProvider properties, AfonCloud cloud, Path afonHome, Function<URI, KeyCloakMemberLogin> login)
        throws IOException, InterruptedException {
        String projectStack = properties.getValue(PROJECT_ID, "");
        String member = properties.getValue(SMOKE_MEMBER, "");
        if (projectStack.isBlank() || member.isBlank()) {
            throw new IOException("There is no smoke member: PROJECT_ID and SMOKE_MEMBER are set in the config of a project stack that lists smokeMembers, once configured");
        }
        Path secrets = FileProjectStackProvider.secretsDir(afonHome, projectStack);
        SmokeMemberCredentials credentials = SmokeMemberCredentials.read(secrets, member).orElseThrow(() -> new IOException(
            "No credentials for " + member + " in " + secrets.resolve(SmokeMemberCredentials.FILE) + ": give them with manage-identity smoke-credentials"));
        String clientSecret = Files.readString(secrets.resolve("tre.secret"), StandardCharsets.UTF_8).strip();
        String jupyterDomain = properties.getCloudValue(cloud, AfonCloudProperty.JUPYTER_DOMAIN_NAME, "");
        if (jupyterDomain.isBlank()) {
            throw new IOException(cloud.name() + "_JUPYTER_DOMAIN_NAME is not set, so there is no callback of the TRE's JupyterHub to sign in with");
        }
        KeyCloakMemberLogin.Token token = login.apply(URI.create(properties.getOidcIssuerUrl(cloud)))
            .login(properties.getValue(OIDC_AUDIENCE), clientSecret, "https://" + jupyterDomain + KeyCloakProvider.JUPYTERHUB_CALLBACK, member, credentials.password(),
                   credentials.totpSecret());
        String principal = token.claims().path("polaris_principal").asText("");
        return new SmokeUser(member, principal.isBlank() ? member : principal, token.accessToken(), true);
    }

    /**
     * The {@code USER_ID} user, with the token of their confidential client, which takes no username and password, as the user's name and password are its id and secret.
     *
     * @param properties as seen by the cloud under test, whose Keycloak it calls
     * @param tokens     gets the token of the user's client
     */
    public static SmokeUser ofConfiguredUser(AfonPropertyProvider properties, AfonUserProvider users, ClientTokens tokens) throws IOException {
        AfonUser user = users.getConfiguredUser(properties);
        return new SmokeUser(user.username(), user.username(), tokens.of(properties, user), false);
    }
}
