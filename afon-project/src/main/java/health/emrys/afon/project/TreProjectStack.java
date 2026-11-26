package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.StackId;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * A project stack with the role {@link ProjectStackRole#TRE}: a trusted research environment, with its place in the central identity, its Data
 * Providers, and its Git.
 *
 * @param id        the project stack's immutable id
 * @param cloud     the cloud of every stack of the project stack
 * @param env       the env whose config the project stack is made from, or {@code null} for the active config
 * @param egressIp  the address that the project stack's cluster leaves from, or {@code null}, see {@link ProjectStack#egressIp()}
 * @param nodePool  the nodes of its cluster, see {@link ProjectStack#nodePool()}
 * @param dnsDelegation how the DNS zones of its domains are delegated, see {@link ProjectStack#dnsDelegation()}
 * @param identity  how the project stack uses the central identity that issues its tokens
 * @param dataProviders the Data Providers' project stack catalogs, reached directly
 * @param members   the project stack's members, at least one, which {@code up} makes the project stack's group have, with the smoke members, and no others
 * @param smokeMembers the members that exist for the smoke tests to sign in as, none if the file lists none, kept apart from the members that people use
 * @param create    what outlives the nightly teardown
 * @param git       the project stack's own Git, or {@code null} if Git is deployed with the TRE, as it is by the stacks of a TRE that declare it
 * @param tre       the project stack's TRE
 */
public record TreProjectStack(@NonNull ProjectStackId id, @NonNull AfonCloud cloud, @Nullable String env, @Nullable String egressIp,
                         @NonNull Map<String, String> nodePool, @NonNull DnsDelegation dnsDelegation, @NonNull Identity identity, @NonNull List<DataProvider> dataProviders, @NonNull List<Member> members, @NonNull List<Member> smokeMembers, @NonNull Create create, @Nullable Git git,
                         @NonNull Tre tre) implements ProjectStack {

    @Override
    public ProjectStackRole role() {
        return ProjectStackRole.TRE;
    }

    @Override
    public @Nullable String identityProjectStack() {
        return identity.projectStack();
    }

    /**
     * @return the TRE's stack
     */
    @Override
    public StackId stack() {
        return tre.stack();
    }

    /**
     * How a TRE project stack uses the central identity: its issuer, the OIDC {@code acr} (authentication context class reference),
     * the project stack's two clients, and the claims.  The OIDC {@code acr} says how strongly the user authenticated, such as
     * the one that proves a second factor, eg {@code mfa}.
     *
     * @param issuerUrl    the issuer that every consumer of the project stack's tokens trusts
     * @param acr          the value of the OIDC {@code acr}
     * @param treClient    the project stack's TRE client
     * @param gitClient    the project stack's Git client, or {@code null} if it has no Git of its own, see {@link TreProjectStack#git()}
     * @param projectClaim the claim of a token naming the project stack, by the project stack id
     * @param userClaim    the claim of a token naming its user, by the immutable id of the user, never the username
     * @param projectStack      the id of the identity project stack, ie the project stack with the role {@link ProjectStackRole#IDENTITY}, that deploys the
     *                     central identity, or {@code null} if the identity is not one that Afon deployed in a project stack: {@code up} then does
     *                     not admit this machine to its admin API for the registration
     */
    public record Identity(@NonNull String issuerUrl, @NonNull String acr, @NonNull String treClient,
                           @Nullable String gitClient, @NonNull String projectClaim, @NonNull String userClaim,
                           @Nullable String projectStack) {

        public static final String DEFAULT_PROJECT_CLAIM = "project";
        public static final String DEFAULT_USER_CLAIM = "polaris_principal";
    }

    /**
     * A Data Provider's project stack catalog.
     *
     * @param id          the id of the catalog, unique in the project stack
     * @param catalogUri  the Data Provider's catalog endpoint, its Iceberg REST catalog, eg the one of its Polaris
     * @param catalogName the catalog's name at that endpoint
     * @param audience    the audience that the project stack's TRE token carries for the Data Provider
     * @param bucket      the catalog's bucket, for the whitelist
     * @param cloud       the cloud of the Data Provider's storage, which may not be the project stack's
     * @param projectStack     the id of the data project stack that the catalog was read from, when the file names one, or {@code null} if the file gives the
     *                    catalog's endpoint, audience and bucket itself, as it does for a Data Provider that Afon did not deploy
     */
    public record DataProvider(@NonNull String id, @NonNull String catalogUri, @NonNull String catalogName,
                              @NonNull String audience, @NonNull String bucket, @NonNull AfonCloud cloud, @Nullable String projectStack) {

        /** A catalog that the file gives in full. */
        public DataProvider(String id, String catalogUri, String catalogName, String audience, String bucket, AfonCloud cloud) {
            this(id, catalogUri, catalogName, audience, bucket, cloud, null);
        }
    }

    /**
     * A member of the project stack, by the email they are known by in the central identity.
     *
     * @param email     their email, in lower case
     * @param firstName their first name, or {@code null}
     * @param lastName  their last name, or {@code null}
     */
    public record Member(@NonNull String email, @Nullable String firstName, @Nullable String lastName) {
    }

    /**
     * @return the members that people use and the smoke members, who are all in the project stack's group, and so all sign in to its TRE
     */
    @Override
    public @NonNull List<Member> allMembers() {
        List<Member> all = new java.util.ArrayList<>(members);
        all.addAll(smokeMembers);
        return List.copyOf(all);
    }

    /**
     * @return the smoke member that the smoke tests sign in as, to test the TRE as a member would use it: the first that the file lists, none if it lists none
     */
    public java.util.Optional<Member> smokeMember() {
        return smokeMembers.stream().findFirst();
    }

    /**
     * What the project stack's {@code <cloud>-create} makes, which outlives the stacks: its audit bucket, and the bucket of its Git's blobs.
     *
     * @param auditBucket the bucket of the project stack's audit trail
     * @param gitBucket   the bucket of Git's blobs, or {@code null} to leave it as the config it is made from has it
     */
    public record Create(@NonNull String auditBucket, @Nullable String gitBucket) {
    }

    /**
     * The project stack's own Git, in a stack of its own that the nightly teardown leaves alone, rather than with the TRE.
     *
     * @param domain the domain it is served at
     */
    public record Git(@NonNull String domain) {
    }

    /**
     * The project stack's TRE, which lives for a day.
     *
     * @param stack    the stack deployed, eg {@code gcp-full}, of the project stack's cloud
     * @param domains  the domains of its components, by property name
     * @param teardown the time of the nightly teardown, eg {@code 22:00}, or {@code null} if it is not torn down nightly
     * @param timeZone the time zone of {@link #teardown}
     */
    public record Tre(@NonNull StackId stack, @NonNull Map<String, String> domains, @Nullable String teardown,
                      @NonNull String timeZone) {
    }
}
