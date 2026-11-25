package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.StackId;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * A project stack with the role {@link ProjectStackRole#DATA}: a Data Provider, which deploys its Polaris, the stack {@code <cloud>-polaris}, with a catalog for
 * each use of its data.  The TREs read its catalogs, by the catalog endpoint, the catalog's name and the audience that its tokens carry, which a
 * TRE project stack's {@code dataProviders} give.  It has no Data Providers, clients or members of its own.  It is long-lived: it is not rebuilt, and taking it down
 * leaves its storage.
 *
 * @param id       the project stack's immutable id, eg {@code lon}
 * @param cloud    the cloud of its stack, which is that of its storage
 * @param env      the env whose config the project stack is made from, or {@code null} for the active config
 * @param egressIp the address that the Data Provider's cluster leaves from, or {@code null}, see {@link ProjectStack#egressIp()}
 * @param nodePool the nodes of its cluster, see {@link ProjectStack#nodePool()}
 * @param dnsDelegation how the DNS zone of its domain is delegated, see {@link ProjectStack#dnsDelegation()}
 * @param identity the central identity that its Polaris trusts, the same that the TREs sign in to
 * @param catalogs the ids of its catalogs, eg {@code cvd} and {@code dbt}, at least one, in the order that the file lists them
 * @param stack    the stack of the Polaris that it deploys, eg {@code azure-polaris}, of the project stack's cloud
 * @param domain   the domain of its Polaris, {@code <CLOUD>_POLARIS_DOMAIN_NAME}, or {@code null} to derive it from that of the config the project stack is
 *                 made from, with the project stack id as a label after the first, eg {@code azure-polaris.lon.emrys2.click}
 */
public record DataProjectStack(@NonNull ProjectStackId id, @NonNull AfonCloud cloud, @Nullable String env, @Nullable String egressIp,
                          @NonNull Map<String, String> nodePool, @NonNull DnsDelegation dnsDelegation, @NonNull Identity identity,
                          @NonNull List<String> catalogs,
                          @NonNull StackId stack, @Nullable String domain) implements ProjectStack {

    @Override
    public ProjectStackRole role() {
        return ProjectStackRole.DATA;
    }

    @Override
    public @Nullable String identityProjectStack() {
        return identity.projectStack();
    }

    /**
     * How a Data Provider uses the central identity: it trusts the tokens that it issues, so its Polaris has to check the issuer, the audience that is its
     * own, and the OIDC {@code acr} (authentication context class reference) that proves a second factor.
     *
     * @param issuerUrl the issuer of the tokens that the Data Provider's Polaris accepts: that of the central identity
     * @param acr       the value of the OIDC {@code acr} that its Polaris requires, eg {@code mfa}
     * @param audience  the audience that its Polaris requires, which the TREs' tokens carry for it, {@code provider-<id>} if the file gives none
     * @param projectStack   the id of the identity project stack that deploys the central identity, or {@code null} if it is not one that Afon deployed
     */
    public record Identity(@NonNull String issuerUrl, @NonNull String acr, @NonNull String audience, @Nullable String projectStack) {
    }
}
