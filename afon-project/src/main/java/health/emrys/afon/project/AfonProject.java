package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.StackId;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A project: everything Afon needs to spin up the stacks of one research project, as the deployer defines it in
 * {@code project.yaml}, see {@link FileProjectProvider}.  It holds no secret: those stay in the secret store of the project's cloud.
 * <p>
 * The deployer writes the file, from what the contracts say, so that Afon needs nothing of the registry beyond it.
 *
 * @param id        the project's immutable id
 * @param cloud     the cloud of every stack of the project: with the env, all that says where it deploys, as its region, account, node pools and
 *                  names come from the config it is made from
 * @param env       the deployer's env whose config the project is made from, {@code config-<env>} in the Afon home, or {@code null} for the
 *                  config that is active, which is whichever env the deployer last switched to
 * @param identity  the central identity that issues the project's tokens
 * @param providers the Data Providers' project catalogs, reached directly
 * @param create    what outlives the nightly teardown
 * @param git       the project's own Git, or {@code null} if Git is deployed with the TRE, as it is by the stacks of a TRE that declare it
 * @param tre       the project's TRE
 */
public record AfonProject(@NonNull ProjectId id, @NonNull AfonCloud cloud,
                          @Nullable String env,
                          @NonNull Identity identity, @NonNull List<Provider> providers, @NonNull Create create,
                          @Nullable Git git, @NonNull Tre tre) {

    /**
     * The id of a project, eg {@code cvd-2026-0007}: immutable and never reused, so that a new project cannot inherit a grant, a
     * Git organisation or a state that an old one left behind.  It names the project's clients, group, principal role and Git
     * organisation, and keys the state of its stacks, so it is a lower-case DNS label of at most {@link #MAX_LENGTH}
     * characters, which fits every name it is part of (Kubernetes, DNS, buckets, Polaris roles).
     */
    public record ProjectId(@NonNull String value) {

        public static final int MAX_LENGTH = 30;

        private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?");

        /**
         * @throws IllegalArgumentException if the value is not a lower-case DNS label of at most {@link #MAX_LENGTH} characters
         */
        public ProjectId {
            if (value.length() > MAX_LENGTH || !DNS_LABEL.matcher(value).matches()) {
                throw new IllegalArgumentException(String.format(
                    "Project id '%s' is not a lower-case DNS label of at most %d characters, eg cvd-2026-0007", value, MAX_LENGTH));
            }
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /**
     * The central identity: its issuer, the {@code acr} that proves MFA, and the project's two clients.
     *
     * @param issuerUrl the issuer that every consumer of the project's tokens trusts
     * @param acr       the {@code acr} value proving MFA
     * @param treClient the project's TRE client
     * @param gitClient the project's Git client, or {@code null} if it has no Git of its own, see {@link AfonProject#git()}
     * @param projectClaim the claim of a token naming the project, a list of exactly one project id
     * @param userClaim    the claim of a token naming its user, by the immutable id of the user, never the username
     */
    public record Identity(@NonNull String issuerUrl, @NonNull String acr, @NonNull String treClient,
                           @Nullable String gitClient, @NonNull String projectClaim, @NonNull String userClaim) {

        public static final String DEFAULT_PROJECT_CLAIM = "project";
        public static final String DEFAULT_USER_CLAIM = "polaris_principal";
    }

    /**
     * A Provider's project catalog.
     *
     * @param id         the id of the catalog, unique in the project
     * @param polarisUri the Provider's Polaris REST endpoint
     * @param warehouse  the catalog's name in that Polaris
     * @param audience   the audience that the project's TRE token carries for the Provider
     * @param bucket     the catalog's bucket, for the whitelist
     * @param cloud      the cloud of the Provider's storage, which may not be the project's
     */
    public record Provider(@NonNull String id, @NonNull String polarisUri, @NonNull String warehouse,
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
