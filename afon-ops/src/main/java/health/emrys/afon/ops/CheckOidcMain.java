package health.emrys.afon.ops;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.security.IdentityProvider;
import health.emrys.afon.security.keycloak.KeyCloakProvider;

import java.io.PrintStream;


/**
 * Checks that the {@link IdentityProvider} at the OIDC domain name of the cloud is serving requests, eg before
 * scripts/smoke/notebook.sh asks it for a token.  Exits non-zero if it is not.
 */
public class CheckOidcMain {

    private final IdentityProvider identityProvider;
    private final String domainName;
    private final PrintStream out;
    private final PrintStream err;

    public CheckOidcMain(IdentityProvider identityProvider, String domainName, PrintStream out, PrintStream err) {
        this.identityProvider = identityProvider;
        this.domainName = domainName;
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

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("CheckOidcMain requires the argument: cloud");
            return;
        }
        AfonCloud cloud = AfonCloud.valueOf(args[0].toUpperCase());
        String domainName = new OpsPropertyProvider().getOidcDomainName(cloud);
        if (!new CheckOidcMain(new KeyCloakProvider(domainName), domainName, System.out, System.err).check()) {
            System.exit(1);
        }
    }

}
