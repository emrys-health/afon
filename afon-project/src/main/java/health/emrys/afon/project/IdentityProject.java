package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.StackId;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * A project with the role {@link ProjectRole#IDENTITY}: the central identity itself, which every TRE project uses to sign in.  It has no
 * Data Providers, clients or members, and is not torn down nightly.  It holds the egress IP that the identity leaves from, its own, so that
 * what the identity depends on, eg its database, allowlists one address that does not change when it is rebuilt.
 *
 * @param id       the project's immutable id
 * @param cloud    the cloud of its stack
 * @param env      the env whose config the project is made from, or {@code null} for the active config
 * @param egressIp the address that the identity's cluster leaves from, or {@code null}, see {@link AfonProject#egressIp()}
 * @param nodePool the nodes of its cluster, see {@link AfonProject#nodePool()}
 * @param dnsDelegation how the DNS zone of its domain is delegated, see {@link AfonProject#dnsDelegation()}
 * @param stack    the stack of the central identity that it deploys, eg {@code azure-keycloak}, of the project's cloud
 * @param domain   the domain of the identity, {@code <CLOUD>_OIDC_DOMAIN_NAME}, or {@code null} to derive it from that of the config the project is
 *                 made from, with the project id as a label after the first, eg {@code azure-oidc.idp.emrys2.click}
 */
public record IdentityProject(@NonNull ProjectId id, @NonNull AfonCloud cloud, @Nullable String env, @Nullable String egressIp,
                              @NonNull Map<String, String> nodePool, @NonNull DnsDelegation dnsDelegation, @NonNull StackId stack, @Nullable String domain) implements AfonProject {

    @Override
    public ProjectRole role() {
        return ProjectRole.IDENTITY;
    }
}
