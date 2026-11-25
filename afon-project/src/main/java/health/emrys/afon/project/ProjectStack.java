package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.StackId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * A project stack: a stack that is configured in a project, with everything Afon needs to spin it up, as the deployer defines it in
 * {@code project.yaml}, see {@link FileProjectStackProvider}.  A project is the ensemble of the project stacks that make it operational: its
 * identity, its Data Providers and its TRE.  A project stack holds no secret: those stay in the secret store of its cloud.
 * <p>
 * The deployer writes the file, from what the contracts say, so that Afon needs nothing of the registry beyond it.
 * <p>
 * What a project stack is made of depends on its {@link ProjectStackRole}, so there is a type for each, which has just what that role needs:
 * a {@link TreProjectStack}, an {@link IdentityProjectStack} and a {@link DataProjectStack}.  This is what they have in common.
 */
public sealed interface ProjectStack permits TreProjectStack, IdentityProjectStack, DataProjectStack {

    /**
     * @return the project stack's immutable id
     */
    @NonNull ProjectStackId id();

    /**
     * @return the cloud of every stack of the project stack: with the env, all that says where it deploys, as its region, account, node pools and
     * names come from the config it is made from
     */
    @NonNull AfonCloud cloud();

    /**
     * @return the role of the project stack
     */
    @NonNull ProjectStackRole role();

    /**
     * @return the deployer's env whose config the project stack is made from, {@code config-<env>} in the Afon home, or {@code null} for the config
     * that is active, which is whichever env the deployer last switched to
     */
    @Nullable String env();

    /**
     * @return the address that the project stack's cluster leaves from, a static IP that the deployer reserved apart from the stacks, eg with
     * {@code azure-egress-ip create}, so that a rebuild keeps it and whoever allowlists it, eg each Data Provider, need not change it; or
     * {@code null} for the address, if any, that the config the project stack is made from names
     */
    @Nullable String egressIp();

    /**
     * @return the nodes of the project stack's cluster, as the keys of the cloud's node pool property take them, see {@link #NODE_POOL_KEYS}, eg
     * {@code vmSize} and {@code nodeCount} on Azure, or empty for the nodes, if any, that the config the project stack is made from names
     */
    @NonNull Map<String, String> nodePool();

    /**
     * @return how the DNS zones of the project stack's domains are delegated from their parent domain, {@link DnsDelegation#MANUAL} unless the project stack says
     */
    @NonNull DnsDelegation dnsDelegation();

    /**
     * The keys of the node pool of a cloud's cluster, as its node pool property takes them: {@code AZURE_AKS_NODE_POOL},
     * {@code GCP_GKE_NODE_POOL}.  AWS has no such property.
     */
    Map<AfonCloud, Set<String>> NODE_POOL_KEYS = Map.of(AfonCloud.AZURE, Set.of("vmSize", "nodeCount"),
                                                       AfonCloud.GCP, Set.of("machineType", "nodeCount"),
                                                       AfonCloud.AWS, Set.of());

    /**
     * @return the stack that the project stack deploys: its TRE's, or the central identity's
     */
    @NonNull StackId stack();

    /**
     * @return the project stack's members, by email: those of a TRE project stack, none for an identity project stack, which no one uses
     */
    default @NonNull List<TreProjectStack.Member> members() {
        return List.of();
    }

    /**
     * @return the id of the identity project stack, ie the project stack with the role {@link ProjectStackRole#IDENTITY}, that deploys the central identity that the
     * project stack uses, or {@code null} if it uses none that Afon deployed: a TRE project stack and a data project stack use the central identity, the identity
     * project stack is it
     */
    default @Nullable String identityProjectStack() {
        return null;
    }

    /**
     * The id of a project stack, eg {@code xyz-yyyy-nnnn}: immutable and never reused, so that a new project stack cannot inherit a grant, a
     * Git organisation or a state that an old one left behind.  It names the project stack's clients, group, principal role and Git
     * organisation, and keys the state of its stacks, so it is a lower-case DNS label of at most {@link #MAX_LENGTH}
     * characters, which fits every name it is part of (Kubernetes, DNS, buckets, Polaris roles).
     */
    record ProjectStackId(@NonNull String value) {

        public static final int MAX_LENGTH = 30;

        private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?");

        /**
         * @throws IllegalArgumentException if the value is not a lower-case DNS label of at most {@link #MAX_LENGTH} characters
         */
        public ProjectStackId {
            if (value.length() > MAX_LENGTH || !DNS_LABEL.matcher(value).matches()) {
                throw new IllegalArgumentException(String.format(
                    "Project stack id '%s' is not a lower-case DNS label of at most %d characters, eg xyz-yyyy-nnnn", value, MAX_LENGTH));
            }
        }

        @Override
        public String toString() {
            return value;
        }
    }
}
