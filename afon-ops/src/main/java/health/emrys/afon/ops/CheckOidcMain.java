package health.emrys.afon.ops;

import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.security.IdentityProvider;
import health.emrys.afon.security.keycloak.KeyCloakProvider;

import static health.emrys.afon.config.AfonProperty.OIDC_DOMAIN_NAME;

/**
 * Checks that the {@link IdentityProvider} at {@code OIDC_DOMAIN_NAME} is serving requests, eg before
 * smoke/run-oidc-notebook.sh asks it for a token.  Exits non-zero if it is not.
 */
public class CheckOidcMain {

    private final IdentityProvider identityProvider;
    private final String domainName;

    public CheckOidcMain(IdentityProvider identityProvider, String domainName) {
        this.identityProvider = identityProvider;
        this.domainName = domainName;
    }

    public boolean check() {
        boolean ready = identityProvider.isReady();
        // printed as well as logged, as the console logging may be off, eg in the CLI
        if (ready) {
            System.out.printf("OIDC provider at '%s' is ready%n", domainName);
        } else {
            System.err.printf("OIDC provider at '%s' is not ready%n", domainName);
        }
        return ready;
    }

    public static void main(String[] args) {
        String domainName = new OpsPropertyProvider().getValue(OIDC_DOMAIN_NAME);
        if (!new CheckOidcMain(new KeyCloakProvider(domainName), domainName).check()) {
            System.exit(1);
        }
    }

}
