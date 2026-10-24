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

import static health.emrys.afon.config.AfonProperty.*;

/**
 * Shows an OIDC access token from the {@link IdentityProvider}: of the Polaris admin client
 * ({@code POLARIS_ID}), or of the {@code USER_ID} user from users.csv, as JupyterHub injects into a user's pod
 * as {@code OIDC_TOKEN}, eg for scripts/smoke/notebook.sh.
 */
public class ShowOidcTokenMain {

    public static final String USER = "user";

    private final IdentityProvider identityProvider;
    private final AfonPropertyProvider properties;
    private final AfonUserProvider userProvider;

    public ShowOidcTokenMain(IdentityProvider identityProvider, AfonPropertyProvider properties,
                             AfonUserProvider userProvider) {
        this.identityProvider = identityProvider;
        this.properties = properties;
        this.userProvider = userProvider;
    }

    public void adminToken(AfonCloud cloud) throws IOException {
        String clientId = properties.getValue(POLARIS_ID);
        String token = identityProvider.clientToken(clientId, properties.getValue(POLARIS_SECRET));
        System.out.printf("Access token for polarisId '%s' on cloud '%s': %s%n", clientId, cloud, token);
    }

    public void userToken(AfonCloud cloud) throws IOException {
        AfonUser user = userProvider.getConfiguredUser(properties);
        String token = identityProvider.userToken(user.username(), user.password());
        System.out.printf("Access token for user '%s' on cloud '%s': %s%n", user.username(), cloud, token);
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0 || (args.length == 2 && !args[1].equalsIgnoreCase(USER)) || args.length > 2) {
            String message = String.format("""
                    %s requires arguments: cloud [%s]
                    With cloud only, gets the OIDC token of the POLARIS_ID admin client, using POLARIS_SECRET.
                    With '%s', gets the OIDC token of the USER_ID user, using its password from users.csv.
                    """, ShowOidcTokenMain.class.getSimpleName(), USER, USER);
            System.out.println(message);
            return;
        }
        var properties = new OpsPropertyProvider();
        AfonCloud cloud = AfonCloud.valueOf(args[0].toUpperCase());
        var app = new ShowOidcTokenMain(new KeyCloakProvider(properties.getOidcDomainName(cloud)), properties,
                                        new FileUserProvider());
        if (args.length == 2) {
            app.userToken(cloud);
        } else {
            app.adminToken(cloud);
        }
    }

}
