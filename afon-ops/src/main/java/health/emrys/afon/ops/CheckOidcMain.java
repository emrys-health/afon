package health.emrys.afon.ops;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.config.FileUserProvider;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.security.IdentityProvider;
import health.emrys.afon.security.keycloak.KeyCloakProvider;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

import static health.emrys.afon.config.AfonProperty.POLARIS_ID;
import static health.emrys.afon.config.AfonProperty.POLARIS_SECRET;


/**
 * Checks that the {@link IdentityProvider} at the OIDC domain name of the cloud is serving requests, eg before
 * scripts/smoke/notebook.sh asks it for a token.  Exits non-zero if it is not.
 * <p>
 * With {@code --token}, also shows an OIDC access token from the provider: of the Polaris admin client
 * ({@code POLARIS_ID}), or, with {@code --token user}, of the {@code USER_ID} user, or {@code --token <username>}, of that user, from users.csv, as JupyterHub
 * injects into a user's pod as {@code OIDC_TOKEN}, eg for scripts/smoke/notebook.sh.
 */
public class CheckOidcMain {

    public static final String TOKEN_OPTION = "--token";
    public static final String USER = "user";

    private final IdentityProvider identityProvider;
    private final String domainName;
    private final AfonPropertyProvider properties;
    private final AfonUserProvider userProvider;
    private final PrintStream out;
    private final PrintStream err;

    public CheckOidcMain(IdentityProvider identityProvider, String domainName, AfonPropertyProvider properties,
                         AfonUserProvider userProvider, PrintStream out, PrintStream err) {
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
        String token = identityProvider.userToken(user.username(), user.password());
        out.printf("Access token for user '%s' on cloud '%s': %s%n", user.username(), cloud, token);
    }

    public static void main(String[] args) throws IOException {
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
                    user, using its password from users.csv.
                    """, CheckOidcMain.class.getSimpleName(), TOKEN_OPTION, USER, TOKEN_OPTION, TOKEN_OPTION, USER,
                              TOKEN_OPTION);
            System.exit(1);
        }
        var properties = new OpsPropertyProvider();
        AfonCloud cloud = AfonCloud.valueOf(positional.get(0).toUpperCase());
        String domainName = properties.getOidcDomainName(cloud);
        var app = new CheckOidcMain(new KeyCloakProvider(domainName), domainName, properties, new FileUserProvider(),
                                    System.out, System.err);
        if (!app.check()) {
            System.exit(1);
        }
        if (token) {
            if (user) {
                app.userToken(cloud, positional.get(1));
            } else {
                app.adminToken(cloud);
            }
        }
    }

}
