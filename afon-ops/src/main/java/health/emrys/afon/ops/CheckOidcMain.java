package health.emrys.afon.ops;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.ops.project.SmokeMemberCredentials;
import health.emrys.afon.project.FileProjectStackProvider;
import health.emrys.afon.project.ProjectStackEnv;
import health.emrys.afon.project.ProjectStackUserProvider;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.security.IdentityProvider;
import health.emrys.afon.security.keycloak.KeyCloakMemberLogin;
import health.emrys.afon.security.keycloak.KeyCloakProvider;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static health.emrys.afon.config.AfonProperty.OIDC_AUDIENCE;
import static health.emrys.afon.config.AfonProperty.PROJECT_ID;
import static health.emrys.afon.config.AfonProperty.SMOKE_MEMBER;
import static health.emrys.afon.config.AfonProperty.POLARIS_ID;
import static health.emrys.afon.config.AfonProperty.POLARIS_SECRET;


/**
 * Checks that the {@link IdentityProvider} at the OIDC domain name of the cloud is serving requests, eg before
 * scripts/smoke/notebook.sh asks it for a token.  Exits non-zero if it is not.
 * <p>
 * With {@code --token}, also shows an OIDC access token from the provider: of the Polaris admin client
 * ({@code POLARIS_ID}), or, with {@code --token user}, of the {@code USER_ID} user, or {@code --token <username>}, of that user's confidential client, from users.csv (only USER_ID has one), by the client-credentials flow, with no MFA, unlike the token JupyterHub
 * injects into a user's pod as {@code OIDC_TOKEN}, eg for scripts/smoke/notebook.sh.  With {@code --token smoke-member}, in a project, the token of its smoke member, {@code SMOKE_MEMBER},
 * as the project's TRE issues it to a member: it signs in to the TRE's client with the password and the authenticator that {@code manage-identity smoke-credentials} gave
 * the member, and the token has the project and the MFA.
 */
public class CheckOidcMain {

    public static final String TOKEN_OPTION = "--token";
    public static final String USER = "user";
    public static final String SMOKE_MEMBER_TOKEN = "smoke-member";

    private final IdentityProvider identityProvider;
    private final String domainName;
    private final AfonPropertyProvider properties;
    private final AfonUserProvider userProvider;
    private final PrintStream out;
    private final PrintStream err;
    private final Path afonHome;
    private final Function<URI, KeyCloakMemberLogin> memberLogin;

    public CheckOidcMain(IdentityProvider identityProvider, String domainName, AfonPropertyProvider properties,
                         AfonUserProvider userProvider, PrintStream out, PrintStream err) {
        this(identityProvider, domainName, properties, userProvider, out, err, ProjectStackEnv.defaultAfonHome(), KeyCloakMemberLogin::new);
    }

    CheckOidcMain(IdentityProvider identityProvider, String domainName, AfonPropertyProvider properties, AfonUserProvider userProvider, PrintStream out,
                  PrintStream err, Path afonHome, Function<URI, KeyCloakMemberLogin> memberLogin) {
        this.afonHome = afonHome;
        this.memberLogin = memberLogin;
        this.identityProvider = identityProvider;
        this.domainName = domainName;
        this.properties = properties;
        this.userProvider = userProvider;
        this.out = out;
        this.err = err;
    }

    public boolean check() {
        boolean ready = identityProvider.isReady();
        // printed as well as logged, as the console logging may be off, eg in the CLI
        if (ready) {
            out.printf("OIDC provider at '%s' is ready%n", domainName);
        } else {
            err.printf("OIDC provider at '%s' is not ready%n", domainName);
        }
        return ready;
    }

    public void adminToken(AfonCloud cloud) throws IOException {
        String clientId = properties.getValue(POLARIS_ID);
        String token = identityProvider.clientToken(clientId, properties.getValue(POLARIS_SECRET));
        out.printf("Access token for polarisId '%s' on cloud '%s': %s%n", clientId, cloud, token);
    }

    /**
     * @param username the user from users.csv, or {@link #USER} for the configured {@code USER_ID} user
     */
    public void userToken(AfonCloud cloud, String username) throws IOException {
        AfonUser user = USER.equalsIgnoreCase(username) ? userProvider.getConfiguredUser(properties)
                                                          : userProvider.getUser(username);
        // The user's confidential client, of the same name and secret, as it takes no username/password
        String token = identityProvider.clientToken(user.username(), user.password());
        out.printf("Access token for user '%s' on cloud '%s': %s%n", user.username(), cloud, token);
    }

    /**
     * Shows the access token of the project's smoke member, as the project's TRE issues it to a member: by signing in to the TRE's client as a member does, with the
     * password and the one-time password of the authenticator that {@code smoke-credentials} gave them, which the project stack's secrets dir keeps.
     */
    public void smokeMemberToken(AfonCloud cloud) throws IOException, InterruptedException {
        String projectStack = properties.getValue(PROJECT_ID, "");
        String member = properties.getValue(SMOKE_MEMBER, "");
        if (projectStack.isBlank() || member.isBlank()) {
            throw new IOException("There is no smoke member: PROJECT_ID and SMOKE_MEMBER are set in the config of a project stack that lists smokeMembers, once configured");
        }
        Path secrets = FileProjectStackProvider.secretsDir(afonHome, projectStack);
        SmokeMemberCredentials credentials = SmokeMemberCredentials.read(secrets, member).orElseThrow(() -> new IOException(
            "No credentials for " + member + " in " + secrets.resolve(SmokeMemberCredentials.FILE) + ": give them with manage-identity smoke-credentials"));
        String clientSecret = Files.readString(secrets.resolve("tre.secret"), StandardCharsets.UTF_8).strip();
        String jupyterDomain = properties.getCloudValue(cloud, health.emrys.afon.config.AfonCloudProperty.JUPYTER_DOMAIN_NAME, "");
        if (jupyterDomain.isBlank()) {
            throw new IOException(cloud.name() + "_JUPYTER_DOMAIN_NAME is not set, so there is no callback of the TRE's JupyterHub to sign in with");
        }
        KeyCloakMemberLogin.Token token = memberLogin.apply(URI.create(properties.getOidcIssuerUrl(cloud)))
            .login(properties.getValue(OIDC_AUDIENCE), clientSecret, "https://" + jupyterDomain + KeyCloakProvider.JUPYTERHUB_CALLBACK, member, credentials.password(),
                   credentials.totpSecret());
        out.printf("Access token for smoke member '%s' on cloud '%s': %s%n", member, cloud, token.accessToken());
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        boolean token = Arrays.asList(args).contains(TOKEN_OPTION);
        List<String> positional = Arrays.stream(args).filter(arg -> !arg.equals(TOKEN_OPTION)).toList();
        boolean user = positional.size() == 2;
        boolean valid = positional.size() == 1 || (token && user);
        if (!valid) {
            System.err.printf("""
                    %s requires arguments: cloud [%s [%s|<username>]], the option being allowed anywhere
                    Checks that the OIDC provider of the cloud is ready.
                    With '%s', also gets the OIDC token of the POLARIS_ID admin client, using POLARIS_SECRET.
                    With '%s %s', gets the OIDC token of the USER_ID user, and with '%s <username>' that of the
                    user's confidential client, using its password from users.csv as the secret (only USER_ID has one).
                    With '%s %s', in a project, gets the token of its smoke member, signed in as a member is.
                    """, CheckOidcMain.class.getSimpleName(), TOKEN_OPTION, USER, TOKEN_OPTION, TOKEN_OPTION, USER,
                              TOKEN_OPTION, TOKEN_OPTION, SMOKE_MEMBER_TOKEN);
            System.exit(1);
        }
        var properties = new OpsPropertyProvider();
        AfonCloud cloud = AfonCloud.valueOf(positional.get(0).toUpperCase());
        String domainName = properties.getOidcDomainName(cloud);
        var app = new CheckOidcMain(new KeyCloakProvider(domainName), domainName, properties, ProjectStackUserProvider.forActiveConfig(),
                                    System.out, System.err);
        if (!app.check()) {
            System.exit(1);
        }
        if (token) {
            if (user && SMOKE_MEMBER_TOKEN.equalsIgnoreCase(positional.get(1))) {
                app.smokeMemberToken(cloud);
            } else if (user) {
                app.userToken(cloud, positional.get(1));
            } else {
                app.adminToken(cloud);
            }
        }
    }

}
