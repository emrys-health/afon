package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.StackId;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * A project: everything Afon needs to spin up the stacks of one project, as the deployer defines it in {@code project.yaml}, see
 * {@link FileProjectProvider}.  It holds no secret: those stay in the secret store of the project's cloud.
 * <p>
 * The deployer writes the file, from what the contracts say, so that Afon needs nothing of the registry beyond it.
 * <p>
 * What a project is made of depends on its {@link ProjectRole}, so there is a type for each, which has just what that role needs:
 * a {@link TreProject} and an {@link IdentityProject}.  This is what they have in common.
 */
public sealed interface AfonProject permits TreProject, IdentityProject {

    /**
     * @return the project's immutable id
     */
    @NonNull ProjectId id();

    /**
     * @return the cloud of every stack of the project: with the env, all that says where it deploys, as its region, account, node pools and
     * names come from the config it is made from
     */
    @NonNull AfonCloud cloud();

    /**
     * @return the role of the project
     */
    @NonNull ProjectRole role();

    /**
     * @return the deployer's env whose config the project is made from, {@code config-<env>} in the Afon home, or {@code null} for the config
     * that is active, which is whichever env the deployer last switched to
     */
    @Nullable String env();

    /**
     * @return the address that the project's cluster leaves from, a static IP that the deployer reserved apart from the stacks, eg with
     * {@code azure-egress-ip create}, so that a rebuild keeps it and whoever allowlists it, eg each Data Provider, need not change it; or
     * {@code null} for the address, if any, that the config the project is made from names
     */
    @Nullable String egressIp();

    /**
     * @return the nodes of the project's cluster, as the keys of the cloud's node pool property take them, see {@link #NODE_POOL_KEYS}, eg
     * {@code vmSize} and {@code nodeCount} on Azure, or empty for the nodes, if any, that the config the project is made from names
     */
    @NonNull Map<String, String> nodePool();

    /**
     * The keys of the node pool of a cloud's cluster, as its node pool property takes them: {@code AZURE_AKS_NODE_POOL},
     * {@code GCP_GKE_NODE_POOL}.  AWS has no such property.
     */
    Map<AfonCloud, Set<String>> NODE_POOL_KEYS = Map.of(AfonCloud.AZURE, Set.of("vmSize", "nodeCount"),
                                                       AfonCloud.GCP, Set.of("machineType", "nodeCount"),
                                                       AfonCloud.AWS, Set.of());

    /**
     * @return the stack that the project deploys: its TRE's, or the central identity's
     */
    @NonNull StackId stack();

    /**
     * @return what the function of the type of the project gives: one for a TRE project, the other for an identity project
     */
    default <R> R by(Function<TreProject, R> tre, Function<IdentityProject, R> identity) {
        return this instanceof TreProject treProject ? tre.apply(treProject) : identity.apply((IdentityProject) this);
    }

    /**
     * The id of a project, eg {@code xyz-yyyy-nnnn}: immutable and never reused, so that a new project cannot inherit a grant, a
     * Git organisation or a state that an old one left behind.  It names the project's clients, group, principal role and Git
     * organisation, and keys the state of its stacks, so it is a lower-case DNS label of at most {@link #MAX_LENGTH}
     * characters, which fits every name it is part of (Kubernetes, DNS, buckets, Polaris roles).
     */
    record ProjectId(@NonNull String value) {

        public static final int MAX_LENGTH = 30;

        private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?");

        /**
         * @throws IllegalArgumentException if the value is not a lower-case DNS label of at most {@link #MAX_LENGTH} characters
         */
        public ProjectId {
            if (value.length() > MAX_LENGTH || !DNS_LABEL.matcher(value).matches()) {
                throw new IllegalArgumentException(String.format(
                    "Project id '%s' is not a lower-case DNS label of at most %d characters, eg xyz-yyyy-nnnn", value, MAX_LENGTH));
            }
        }

        @Override
        public String toString() {
            return value;
        }
    }
}
