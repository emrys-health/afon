package health.emrys.afon.ops.steps;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.StackId;

import java.util.*;
import java.util.function.Function;

import static health.emrys.afon.config.AfonCloud.*;
import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.ops.steps.StackAction.*;
import static health.emrys.afon.ops.steps.StepsUtils.setupFullStackStepsUsing;

/**
 * Provides the steps of each action on each stack, keyed by {@link StackId}, eg the steps to deploy
 * {@code aws-keycloak}.  It is the single definition of the steps of each stack, used by {@code manage-stack}
 * and by the {@code Delete|Deploy|Setup*StackMain} commands it replaces.
 * <p>
 * Stacks run side by side, unless they conflict, ie they hold a common exclusive resource, such as the DNS record of
 * a domain name, or the pinned egress IP, which only one running stack can hold.  So deploying or setting up a stack
 * first deletes the stacks it conflicts with, eg deploying {@code aws-polaris} deletes {@code aws-full}, which also
 * holds the Polaris domain name, but not {@code aws-keycloak}.  Deleting a stack deletes just that stack, and
 * deleting {@code <cloud>-all}, eg {@code aws-all}, deletes every stack of the cloud.
 */
public class StackStepsFactory {

    private final Map<StackId, Map<StackAction, StepsProvider>> stepsProviders = new TreeMap<>();

    /**
     * The exclusive resources each stack holds, named by the properties configuring them, eg
     * {@link AfonProperty#OIDC_DOMAIN_NAME} for the DNS record of the KeyCloak domain name.
     */
    private final Map<StackId, Set<AfonProperty>> exclusiveResources = new TreeMap<>();

    public StackStepsFactory() {
        StackId awsKeycloak = new StackId(AWS, "keycloak");
        holds(awsKeycloak, OIDC_DOMAIN_NAME);
        register(awsKeycloak, DEPLOY, ops -> deploy(ops, awsKeycloak, o -> o.deployKeycloakStackIn(AWS), StepOperations::waitForKeyCloak));
        register(awsKeycloak, SETUP, ops -> List.of(
            deleteConflicting(ops, awsKeycloak),
            ops.deployKeycloakStackIn(AWS),
            ops.waitForKeyCloak(),
            ops.configureOidcUsersFor(AWS),
            ops.setupComplete(AWS)));

        // Unlike aws-polaris, aws-full deploys its own KeyCloak, as do the aws-jupyter and aws-spark stacks it extends
        StackId awsFull = new StackId(AWS, "full");
        holds(awsFull, OIDC_DOMAIN_NAME, AWS_POLARIS_DOMAIN_NAME, AWS_JUPYTER_DOMAIN_NAME, AWS_EGRESS_IP);
        register(awsFull, DEPLOY, ops -> deploy(ops, awsFull, o -> o.deployFullStackIn(AWS), o -> o.waitForPolarisAndKeycloakIn(AWS)));
        register(awsFull, SETUP, ops -> setupFullStackStepsUsing(ops, AWS, deleteConflicting(ops, awsFull)));

        // The Polaris stacks use the KeyCloak server at OIDC_DOMAIN_NAME, which the aws-keycloak stack deploys
        holds(new StackId(AWS, "polaris"), AWS_POLARIS_DOMAIN_NAME, AWS_EGRESS_IP);
        holds(new StackId(AZURE, "polaris"), AZURE_POLARIS_DOMAIN_NAME);
        holds(new StackId(GCP, "polaris"), GCP_POLARIS_DOMAIN_NAME);
        for (AfonCloud cloud : AfonCloud.values()) {
            StackId polaris = new StackId(cloud, "polaris");
            register(polaris, DEPLOY, ops -> deploy(ops, polaris, o -> o.deployPolarisStackIn(cloud), o -> o.waitForPolarisAndKeycloakIn(cloud)));
            register(polaris, SETUP, ops -> setupWithPolaris(ops, polaris, o -> o.deployPolarisStackIn(cloud)));
        }

        stepsProviders.forEach((stackId, actions) -> actions.put(DELETE, deleteOnly(stackId)));
        for (AfonCloud cloud : AfonCloud.values()) {
            register(StackId.all(cloud), DELETE, deleteIn(cloud));
        }
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
     * The steps deleting every stack in the cloud, as run by {@code manage-stack delete <cloud>-all}.
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

    /**
     * @return whether the stack deployed from a stack dir or tofu state named as given, eg {@code aws-full}, conflicts
     * with the stack, ie it is the same stack, or they hold a common exclusive resource.  The legacy stack, named as
     * the cloud, eg {@code aws}, and any stack whose resources are unknown, eg {@code aws-jupyter}, may hold any
     * resource, so conflict with every stack.
     */
    public boolean conflicts(StackId stackId, String stackName) {
        if (stackName.equals(stackId.cloud().toString())) {
            return true;
        }
        StackId other = StackId.parse(stackName);
        Set<AfonProperty> otherResources = exclusiveResources.get(other);
        return otherResources == null
               || other.equals(stackId)
               || !Collections.disjoint(otherResources, exclusiveResources.getOrDefault(stackId, Set.of()));
    }

    /**
     * @return whether the stack uses the KeyCloak server another stack deploys, as it doesn't deploy its own
     */
    boolean usesExternalKeyCloak(StackId stackId) {
        return !exclusiveResources.getOrDefault(stackId, Set.of()).contains(OIDC_DOMAIN_NAME);
    }

    private void holds(StackId stackId, AfonProperty... resources) {
        exclusiveResources.put(stackId, Set.of(resources));
    }

    private void register(StackId stackId, StackAction action, StepsProvider stepsProvider) {
        stepsProviders.computeIfAbsent(stackId, id -> new EnumMap<>(StackAction.class)).put(action, stepsProvider);
    }

    /**
     * The steps deleting just the stack, and the legacy stack of its cloud, if still deployed, which may be any stack.
     */
    private StepsProvider deleteOnly(StackId stackId) {
        StackFilter filter = new StackFilter(
            String.format("stack %s and the legacy stack", stackId),
            name -> name.equals(stackId.toString()) || name.equals(stackId.cloud().toString()));
        return ops -> List.of(
            ops.deleteStacksIn(stackId.cloud(), String.format("delete_%s_stack", stepName(stackId)), filter),
            ops.deleteComplete(stackId.cloud()));
    }

    /**
     * The step deleting the stacks conflicting with the stack, before deploying it.
     */
    private Step deleteConflicting(StepOperations ops, StackId stackId) {
        StackFilter filter = new StackFilter("stacks conflicting with " + stackId, name -> conflicts(stackId, name));
        return ops.deleteStacksIn(stackId.cloud(), "delete_stacks_conflicting_with_" + stepName(stackId), filter);
    }

    private static String stepName(StackId stackId) {
        return stackId.toString().replace('-', '_');
    }

    // The stack-specific steps are passed as functions, so that all the steps are created in the order they
    // run, as StepOperations logs as it creates them.

    private List<Step> deploy(StepOperations ops, StackId stackId,
                              Function<StepOperations, Step> deployStack,
                              Function<StepOperations, Step> waitForStack) {
        List<Step> steps = new ArrayList<>();
        steps.add(deleteConflicting(ops, stackId));
        if (usesExternalKeyCloak(stackId)) {
            steps.add(ops.checkKeyCloakAnswers());
        }
        steps.add(deployStack.apply(ops));
        steps.add(waitForStack.apply(ops));
        steps.add(ops.deployComplete(stackId.cloud()));
        return steps;
    }

    private List<Step> setupWithPolaris(StepOperations ops, StackId stackId,
                                        Function<StepOperations, Step> deployStack) {
        AfonCloud cloud = stackId.cloud();
        List<Step> steps = new ArrayList<>();
        steps.add(deleteConflicting(ops, stackId));
        if (usesExternalKeyCloak(stackId)) {
            steps.add(ops.checkKeyCloakAnswers());
        }
        steps.add(deployStack.apply(ops));
        steps.add(ops.waitForPolarisAndKeycloakIn(cloud));
        steps.add(ops.configureOidcUsersFor(cloud));
        steps.add(ops.createAllCatalogsFor(cloud));
        steps.add(ops.configurePolarisUsersOnAllCatalogsFor(cloud));
        steps.add(ops.setupComplete(cloud));
        return steps;
    }

}
