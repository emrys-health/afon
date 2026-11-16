package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.StackId;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * A project with the role {@link ProjectRole#TRE}: a trusted research environment, with its place in the central identity, its Data
 * Providers, and its Git.
 *
 * @param id        the project's immutable id
 * @param cloud     the cloud of every stack of the project
 * @param env       the env whose config the project is made from, or {@code null} for the active config
 * @param egressIp  the address that the project's cluster leaves from, or {@code null}, see {@link AfonProject#egressIp()}
 * @param nodePool  the nodes of its cluster, see {@link AfonProject#nodePool()}
 * @param dnsDelegation how the DNS zones of its domains are delegated, see {@link AfonProject#dnsDelegation()}
 * @param identity  how the project uses the central identity that issues its tokens
 * @param providers the Data Providers' project catalogs, reached directly
 * @param create    what outlives the nightly teardown
 * @param git       the project's own Git, or {@code null} if Git is deployed with the TRE, as it is by the stacks of a TRE that declare it
 * @param tre       the project's TRE
 */
public record TreProject(@NonNull ProjectId id, @NonNull AfonCloud cloud, @Nullable String env, @Nullable String egressIp,
                         @NonNull Map<String, String> nodePool, @NonNull DnsDelegation dnsDelegation, @NonNull Identity identity, @NonNull List<Provider> providers, @NonNull Create create, @Nullable Git git,
                         @NonNull Tre tre) implements AfonProject {

    @Override
    public ProjectRole role() {
        return ProjectRole.TRE;
    }

    /**
     * @return the TRE's stack
     */
    @Override
    public StackId stack() {
        return tre.stack();
    }

    /**
     * How a TRE project uses the central identity: its issuer, the OIDC {@code acr} (authentication context class reference),
     * the project's two clients, and the claims.  The OIDC {@code acr} says how strongly the user authenticated, such as
     * the one that proves a second factor, eg {@code mfa}.
     *
     * @param issuerUrl    the issuer that every consumer of the project's tokens trusts
     * @param acr          the value of the OIDC {@code acr}
     * @param treClient    the project's TRE client
     * @param gitClient    the project's Git client, or {@code null} if it has no Git of its own, see {@link TreProject#git()}
     * @param projectClaim the claim of a token naming the project, by the project id
     * @param userClaim    the claim of a token naming its user, by the immutable id of the user, never the username
     * @param project      the id of the identity project, ie the project with the role {@link ProjectRole#IDENTITY}, that deploys the
     *                     central identity, or {@code null} if the identity is not one that Afon deployed in a project: {@code up} then does
     *                     not admit this machine to its admin API for the registration
     */
    public record Identity(@NonNull String issuerUrl, @NonNull String acr, @NonNull String treClient,
                           @Nullable String gitClient, @NonNull String projectClaim, @NonNull String userClaim,
                           @Nullable String project) {

        public static final String DEFAULT_PROJECT_CLAIM = "project";
        public static final String DEFAULT_USER_CLAIM = "polaris_principal";
    }

    /**
     * A Provider's project catalog.
     *
     * @param id          the id of the catalog, unique in the project
     * @param catalogUri  the Provider's catalog endpoint, its Iceberg REST catalog, eg the one of its Polaris
     * @param catalogName the catalog's name at that endpoint
     * @param audience    the audience that the project's TRE token carries for the Provider
     * @param bucket      the catalog's bucket, for the whitelist
     * @param cloud       the cloud of the Provider's storage, which may not be the project's
     */
    public record Provider(@NonNull String id, @NonNull String catalogUri, @NonNull String catalogName,
                           @NonNull String audience, @NonNull String bucket, @NonNull AfonCloud cloud) {
    }

    /**
     * What the project's {@code <cloud>-create} makes, which outlives the stacks: its audit bucket, and the bucket of its Git's blobs.
     *
     * @param auditBucket the bucket of the project's audit trail
     * @param gitBucket   the bucket of Git's blobs, or {@code null} to leave it as the config it is made from has it
     */
    public record Create(@NonNull String auditBucket, @Nullable String gitBucket) {
    }

    /**
     * The project's own Git, in a stack of its own that the nightly teardown leaves alone, rather than with the TRE.
     *
     * @param domain the domain it is served at
     */
    public record Git(@NonNull String domain) {
    }

    /**
     * The project's TRE, which lives for a day.
     *
     * @param stack    the stack deployed, eg {@code gcp-full}, of the project's cloud
     * @param domains  the domains of its components, by property name
     * @param teardown the time of the nightly teardown, eg {@code 22:00}, or {@code null} if it is not torn down nightly
     * @param timeZone the time zone of {@link #teardown}
     */
    public record Tre(@NonNull StackId stack, @NonNull Map<String, String> domains, @Nullable String teardown,
                      @NonNull String timeZone) {
    }
}
