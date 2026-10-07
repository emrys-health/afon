package health.emrys.afon.cdk;

import health.emrys.afon.cdk.component.StackComponent;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.StackId;

import java.util.List;
import java.util.function.Function;

/**
 * What a stack is composed of: the components deployed to its cloud, in order, as each may depend on those before it.
 * The components are those of the config, as which are deployed may depend on it, eg whether the stack deploys its own
 * KeyCloak.
 *
 * @param id             the stack, whose cloud it is deployed to
 * @param pinnedEgressIp for AWS, whether the stack's egress IP is pinned to {@code AWS_EGRESS_IP}, if set, which only
 *                       one running stack can hold, else the stack allocates its own
 * @param components     creates the components, new for each stack built as they hold what they deploy
 */
public record StackDefinition(StackId id,
                              boolean pinnedEgressIp,
                              Function<AfonPropertyProvider, List<StackComponent>> components) {
}
