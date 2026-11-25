package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.StackId;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * A project stack with the role {@link ProjectStackRole#IDENTITY}: the central identity itself, which every TRE project stack uses to sign in.  It has no
 * Data Providers, clients or members, and is not torn down nightly.  It holds the egress IP that the identity leaves from, its own, so that
 * what the identity depends on, eg its database, allowlists one address that does not change when it is rebuilt.
 *
 * @param id       the project stack's immutable id
 * @param cloud    the cloud of its stack
 * @param env      the env whose config the project stack is made from, or {@code null} for the active config
 * @param egressIp the address that the identity's cluster leaves from, or {@code null}, see {@link ProjectStack#egressIp()}
 * @param nodePool the nodes of its cluster, see {@link ProjectStack#nodePool()}
 * @param dnsDelegation how the DNS zone of its domain is delegated, see {@link ProjectStack#dnsDelegation()}
 * @param stack    the stack of the central identity that it deploys, eg {@code azure-keycloak}, of the project stack's cloud
 * @param domain   the domain of the identity, {@code <CLOUD>_OIDC_DOMAIN_NAME}, or {@code null} to derive it from that of the config the project stack is
 *                 made from, with the project stack id as a label after the first, eg {@code azure-oidc.idp.emrys2.click}
 */
public record IdentityProjectStack(@NonNull ProjectStackId id, @NonNull AfonCloud cloud, @Nullable String env, @Nullable String egressIp,
                              @NonNull Map<String, String> nodePool, @NonNull DnsDelegation dnsDelegation, @NonNull StackId stack, @Nullable String domain) implements ProjectStack {

    @Override
    public ProjectStackRole role() {
        return ProjectStackRole.IDENTITY;
    }
}
