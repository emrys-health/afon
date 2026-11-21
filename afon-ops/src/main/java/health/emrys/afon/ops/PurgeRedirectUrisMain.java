package health.emrys.afon.ops;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonCloudProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.security.IdentityProvider;
import health.emrys.afon.security.keycloak.KeyCloakProvider;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static health.emrys.afon.config.AfonProperty.OAUTH2_CLIENT_ID;
import static health.emrys.afon.config.AfonProperty.OAUTH2_CLIENT_SECRET;
import static health.emrys.afon.config.AfonProperty.OIDC_PROJECT_CLAIM;

/**
 * Takes the stale redirect URIs off the Jupyter browser client of the KeyCloak of a cloud: those that are not the callbacks of the Jupyter domain
 * of any cloud of the config, eg of a cloud that is no longer deployed, which {@code OIDC_REDIRECT_STRATEGY=merge} leaves on the client.
 */
public class PurgeRedirectUrisMain {

    public static final String DRY_RUN_OPTION = "--dry-run";

    private final IdentityProvider   identityProvider;
    private final AfonPropertyProvider properties;
    private final PrintStream        out;

    public PurgeRedirectUrisMain(IdentityProvider identityProvider, AfonPropertyProvider properties, PrintStream out) {
        this.identityProvider = identityProvider;
        this.properties       = properties;
        this.out              = out;
    }

    /**
     * The callbacks of the Jupyter of every cloud that has a Jupyter domain name, which the client is to keep.
     */
    List<String> callbacksToKeep() {
        Set<String> keep = new LinkedHashSet<>();
        for (AfonCloud cloud : AfonCloud.values()) {
            AfonCloudProperty.JUPYTER_DOMAIN_NAME.forCloud(cloud)
                .map(property -> properties.getValue(property, ""))
                .filter(domain -> !domain.isBlank())
                .ifPresent(domain -> keep.addAll(KeyCloakProvider.callbackURIs(domain.trim())));
        }
        return new ArrayList<>(keep);
    }

    /**
     * @return the redirect URIs taken off the client, or, in a dry run, that would be
     */
    public List<String> purge(boolean dryRun) throws IOException {
        if (!properties.getValue(OIDC_PROJECT_CLAIM, "").isBlank()) {
            out.println("This is a project's TRE, whose browser client is made by the central identity, so there is nothing to purge here");
            return List.of();
        }
        List<String> keep = callbacksToKeep();
        if (keep.isEmpty()) {
            // taking off every redirect URI would stop everybody logging in
            throw new IllegalStateException("No cloud has a Jupyter domain name (<CLOUD>_JUPYTER_DOMAIN_NAME), so there is nothing to tell the stale URIs by");
        }
        List<String> purged = identityProvider.purgeBrowserClientRedirectUris(
            properties.getValue(OAUTH2_CLIENT_ID), properties.getValue(OAUTH2_CLIENT_SECRET), keep, dryRun);
        if (purged.isEmpty()) {
            out.println("No stale redirect URIs on the browser client");
        } else {
            out.printf("%s %d stale redirect URI(s) of the browser client:%n", dryRun ? "Would take off" : "Took off", purged.size());
            purged.forEach(uri -> out.println("  " + uri));
        }
        return purged;
    }

    public static void main(String[] allArgs) throws IOException {
        String endpoint = Arrays.stream(allArgs)
                                .filter(arg -> arg.startsWith(ConfigureUsersMain.KEYCLOAK_ENDPOINT_ARG))
                                .map(arg -> arg.substring(ConfigureUsersMain.KEYCLOAK_ENDPOINT_ARG.length()).trim())
                                .filter(value -> !value.isEmpty())
                                .reduce((first, last) -> last)
                                .orElse(null);
        boolean dryRun = Arrays.asList(allArgs).contains(DRY_RUN_OPTION);
        List<String> positional = Arrays.stream(allArgs)
                                        .filter(arg -> !arg.startsWith(ConfigureUsersMain.KEYCLOAK_ENDPOINT_ARG) && !arg.equals(DRY_RUN_OPTION))
                                        .toList();
        if (positional.size() != 1) {
            System.err.printf("%s requires the argument: cloud, and takes %s and %s<host:port>%n", PurgeRedirectUrisMain.class.getSimpleName(),
                              DRY_RUN_OPTION, ConfigureUsersMain.KEYCLOAK_ENDPOINT_ARG);
            System.exit(1);
        }
        AfonCloud cloud = AfonCloud.valueOf(positional.get(0).toUpperCase());
        // as seen by the cloud, whose KeyCloak it purges
        AfonPropertyProvider properties = new OpsPropertyProvider().forCloud(cloud);
        new PurgeRedirectUrisMain(new KeyCloakProvider(properties, endpoint), properties, System.out).purge(dryRun);
    }
}
