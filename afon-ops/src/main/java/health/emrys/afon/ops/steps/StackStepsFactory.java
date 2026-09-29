package health.emrys.afon.ops.steps;

import health.emrys.afon.config.AfonCloud;

import java.util.*;
import java.util.function.Function;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.ops.steps.StackAction.*;
import static health.emrys.afon.ops.steps.StepsUtils.setupFullStackStepsUsing;

/**
 * Provides the steps of each action on each stack, keyed by {@link StackId}, eg the steps to deploy
 * {@code aws-keycloak}.  It is the single definition of the steps of each stack, used by {@code manage-stack}
 * and by the {@code Delete|Deploy|Setup*StackMain} commands it replaces.
 */
public class StackStepsFactory {

    private final Map<StackId, Map<StackAction, StepsProvider>> stepsProviders = new TreeMap<>();

    public StackStepsFactory() {
        for (AfonCloud cloud : AfonCloud.values()) {
            StackId polaris = new StackId(cloud, "polaris");
            register(polaris, DEPLOY, ops -> deploy(ops, cloud, o -> o.deployPolarisStackIn(cloud), o -> o.waitForPolarisAndKeycloakIn(cloud)));
            register(polaris, SETUP, ops -> setupWithPolaris(ops, cloud, o -> o.deployPolarisStackIn(cloud)));
        }

        StackId awsFull = new StackId(AWS, "full");
        register(awsFull, DEPLOY, ops -> deploy(ops, AWS, o -> o.deployFullStackIn(AWS), o -> o.waitForPolarisAndKeycloakIn(AWS)));
        register(awsFull, SETUP, ops -> setupFullStackStepsUsing(ops, AWS));

        StackId awsKeycloak = new StackId(AWS, "keycloak");
        register(awsKeycloak, DEPLOY, ops -> deploy(ops, AWS, o -> o.deployKeycloakStackIn(AWS), StepOperations::waitForKeyCloak));
        register(awsKeycloak, SETUP, ops -> List.of(
            ops.deleteStackIn(AWS),
            ops.deployKeycloakStackIn(AWS),
            ops.waitForKeyCloak(),
            ops.configureOidcUsersFor(AWS),
            ops.setupComplete(AWS)));

        // Each cloud has a single tofu state, so deleting any stack of a cloud deletes whichever is deployed there
        stepsProviders.forEach((stackId, actions) -> actions.put(DELETE, deleteIn(stackId.cloud())));
    }

    /**
     * @return the steps provider of the action on the stack, or {@code null} if the stack is unknown,
     * or the action is not supported for it
     */
    public StepsProvider stepsProviderFor(StackAction action, StackId stackId) {
        return stepsProviders.getOrDefault(stackId, Map.of()).get(action);
    }

    /**
     * @return the steps of the action on the stack
     * @throws IllegalArgumentException if the stack is unknown, or the action is not supported for it
     */
    public List<Step> steps(StackAction action, StackId stackId, StepOperations ops) {
        StepsProvider stepsProvider = stepsProviderFor(action, stackId);
        if (stepsProvider == null) {
            throw new IllegalArgumentException(String.format("Unsupported action '%s' on stack '%s'", action, stackId));
        }
        return stepsProvider.stepsUsing(ops);
    }

    /**
     * The steps deleting the stack in the cloud, whichever it is, as each cloud has a single tofu state.
     */
    public StepsProvider deleteIn(AfonCloud cloud) {
        return ops -> List.of(
            ops.deleteStackIn(cloud),
            ops.deleteComplete(cloud));
    }

    /**
     * @return the actions supported by each stack, in the order of {@link StackAction}
     */
    public SortedMap<StackId, List<StackAction>> supportedActions() {
        SortedMap<StackId, List<StackAction>> supported = new TreeMap<>();
        stepsProviders.forEach((stackId, actions) -> supported.put(
            stackId, Arrays.stream(StackAction.values()).filter(actions::containsKey).toList()));
        return supported;
    }

    private void register(StackId stackId, StackAction action, StepsProvider stepsProvider) {
        stepsProviders.computeIfAbsent(stackId, id -> new EnumMap<>(StackAction.class)).put(action, stepsProvider);
    }

    // The stack-specific steps are passed as functions, so that all the steps are created in the order they
    // run, as StepOperations logs as it creates them.

    private static List<Step> deploy(StepOperations ops, AfonCloud cloud,
                                     Function<StepOperations, Step> deployStack,
                                     Function<StepOperations, Step> waitForStack) {
        return List.of(
            ops.deleteStackIn(cloud),
            deployStack.apply(ops),
            waitForStack.apply(ops),
            ops.deployComplete(cloud));
    }

    private static List<Step> setupWithPolaris(StepOperations ops, AfonCloud cloud,
                                               Function<StepOperations, Step> deployStack) {
        return List.of(
            ops.deleteStackIn(cloud),
            deployStack.apply(ops),
            ops.waitForPolarisAndKeycloakIn(cloud),
            ops.configureOidcUsersFor(cloud),
            ops.createAllCatalogsFor(cloud),
            ops.configurePolarisUsersOnAllCatalogsFor(cloud),
            ops.setupComplete(cloud));
    }

}
