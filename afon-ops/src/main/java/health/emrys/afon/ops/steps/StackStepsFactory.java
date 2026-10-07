package health.emrys.afon.ops.steps;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.cdk.StackDefinitions;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.StackId;
import org.slf4j.Logger;

import java.util.*;
import java.util.function.Function;

import static health.emrys.afon.config.AfonCloud.*;
import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.ops.steps.StackAction.*;
import static health.emrys.afon.ops.steps.StepsUtils.setupFullStackStepsUsing;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Provides the steps of each action on each stack, keyed by {@link StackId}, eg the steps to deploy
 * {@code aws-keycloak}.  It is the single definition of the steps of each stack, used by {@code manage-stack}
 * and by the {@code Delete|Deploy|Setup*StackMain} commands it replaces.
 * <p>
 * Stacks run side by side, unless they conflict, ie they hold a common exclusive resource, such as the DNS record of
 * a domain name, or the pinned egress IP, which only one running stack can hold.  So deploying or setting up a stack
 * first deletes the stacks it conflicts with, eg deploying {@code aws-polaris} deletes {@code aws-full}, which also
 * holds the Polaris domain name, but not {@code aws-keycloak}.  It only deletes the stack itself, if running, unless
 * told to replace the other conflicting stacks too, else it fails, naming them, before deleting any.
 * Deleting a stack deletes just that stack, and deleting {@code <cloud>-all}, eg {@code aws-all}, deletes every stack
 * of the cloud.
 */
public class StackStepsFactory {

    private static final Logger logger = getLogger(StackStepsFactory.class);

    /**
     * The steps of an action on a stack, given whether to replace the other conflicting stacks, when deploying it.
     */
    private interface StackSteps {
        List<Step> stepsUsing(StepOperations ops, boolean replace);
    }

    private final Map<StackId, Map<StackAction, StackSteps>> stackSteps = new TreeMap<>();

    /**
     * The exclusive resources each stack holds, named by the properties configuring them, eg
     * {@link AfonProperty#OIDC_DOMAIN_NAME} for the DNS record of the KeyCloak domain name.
     */
    private final Map<StackId, Set<AfonProperty>> exclusiveResources = new TreeMap<>();

    /**
     * The AWS stacks deploying their own KeyCloak, unless {@link AfonProperty#AWS_KEYCLOAK_EXTERNAL} is set.
     */
    private final Set<StackId> ownKeyCloakUnlessExternal = new TreeSet<>();

    public StackStepsFactory() {
        StackId awsKeycloak = new StackId(AWS, "keycloak");
        holds(awsKeycloak, OIDC_DOMAIN_NAME);
        register(awsKeycloak, DEPLOY, (ops, replace) -> deploy(ops, replace, awsKeycloak, o -> o.deployKeycloakStackIn(AWS), StepOperations::waitForKeyCloak));
        register(awsKeycloak, SETUP, (ops, replace) -> List.of(
            deleteConflicting(ops, replace, awsKeycloak),
            ops.deployKeycloakStackIn(AWS),
            ops.waitForKeyCloak(),
            ops.configureOidcUsersFor(AWS),
            ops.setupComplete(awsKeycloak)));

        // Unlike aws-polaris, aws-full deploys its own KeyCloak, as do the aws-jupyter and aws-spark stacks it extends,
        // unless AWS_KEYCLOAK_EXTERNAL is set
        StackId awsFull = new StackId(AWS, "full");
        holds(awsFull, OIDC_DOMAIN_NAME, AWS_POLARIS_DOMAIN_NAME, AWS_JUPYTER_DOMAIN_NAME, AWS_EGRESS_IP);
        ownKeyCloakUnlessExternal.add(awsFull);
        register(awsFull, DEPLOY, (ops, replace) -> deploy(ops, replace, awsFull, o -> o.deployFullStackIn(AWS), o -> o.waitForPolarisAndKeycloakIn(AWS)));
        register(awsFull, SETUP, (ops, replace) -> setupFullStackStepsUsing(ops, AWS, beforeDeploy(ops, replace, awsFull)));

        // The Polaris stacks use the KeyCloak server at OIDC_DOMAIN_NAME, which the aws-keycloak stack deploys
        holds(new StackId(AWS, "polaris"), AWS_POLARIS_DOMAIN_NAME, AWS_EGRESS_IP);
        holds(new StackId(AZURE, "polaris"), AZURE_POLARIS_DOMAIN_NAME);
        holds(new StackId(GCP, "polaris"), GCP_POLARIS_DOMAIN_NAME);
        for (AfonCloud cloud : AfonCloud.values()) {
            StackId polaris = new StackId(cloud, "polaris");
            register(polaris, DEPLOY, (ops, replace) -> deploy(ops, replace, polaris, o -> o.deployPolarisStackIn(cloud), o -> o.waitForPolarisAndKeycloakIn(cloud)));
            register(polaris, SETUP, (ops, replace) -> setupWithPolaris(ops, replace, polaris, o -> o.deployPolarisStackIn(cloud)));
        }

        stackSteps.forEach((stackId, actions) -> actions.put(DELETE, (ops, replace) -> deleteOnly(stackId).stepsUsing(ops)));
        for (AfonCloud cloud : AfonCloud.values()) {
            register(StackId.all(cloud), DELETE, (ops, replace) -> deleteIn(cloud).stepsUsing(ops));
        }
    }

    /**
     * @return the steps provider of the action on the stack, not replacing the other conflicting stacks, or
     * {@code null} if the stack is unknown, or the action is not supported for it
     */
    public StepsProvider stepsProviderFor(StackAction action, StackId stackId) {
        return stepsProviderFor(action, stackId, false);
    }

    /**
     * @param replace whether deploying or setting up the stack replaces the other stacks conflicting with it, else
     *                it fails, naming them, before deleting any
     * @return the steps provider of the action on the stack, or {@code null} if the stack is unknown, or the action
     * is not supported for it
     */
    public StepsProvider stepsProviderFor(StackAction action, StackId stackId, boolean replace) {
        StackSteps steps = stackSteps.getOrDefault(stackId, Map.of()).get(action);
        return steps == null ? null : ops -> steps.stepsUsing(ops, replace);
    }

    /**
     * @return the steps of the action on the stack, not replacing the other conflicting stacks
     * @throws IllegalArgumentException if the stack is unknown, or the action is not supported for it
     */
    public List<Step> steps(StackAction action, StackId stackId, StepOperations ops) {
        return steps(action, stackId, ops, false);
    }

    /**
     * @return the steps of the action on the stack
     * @throws IllegalArgumentException if the stack is unknown, or the action is not supported for it
     */
    public List<Step> steps(StackAction action, StackId stackId, StepOperations ops, boolean replace) {
        StepsProvider stepsProvider = stepsProviderFor(action, stackId, replace);
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
            ops.deleteComplete(StackId.all(cloud)));
    }

    /**
     * @return the actions supported by each stack, in the order of {@link StackAction}
     */
    public SortedMap<StackId, List<StackAction>> supportedActions() {
        SortedMap<StackId, List<StackAction>> supported = new TreeMap<>();
        stackSteps.forEach((stackId, actions) -> supported.put(
            stackId, Arrays.stream(StackAction.values()).filter(actions::containsKey).toList()));
        return supported;
    }

    /**
     * @return whether the stack deployed from a stack dir or tofu state named as given, eg {@code aws-full}, conflicts
     * with the stack, ie it is the same stack, or they hold a common exclusive resource.  The legacy stack, named as
     * the cloud, eg {@code aws}, and any stack whose resources are unknown, eg {@code aws-jupyter}, may hold any
     * resource, so conflict with every stack.
     * @param properties the config, eg of {@link AfonProperty#AWS_KEYCLOAK_EXTERNAL}
     */
    public boolean conflicts(StackId stackId, String stackName, AfonPropertyProvider properties) {
        if (stackName.equals(stackId.cloud().toString())) {
            return true;
        }
        StackId other = StackId.parse(stackName);
        return !exclusiveResources.containsKey(other)
               || other.equals(stackId)
               || !sharedResources(stackId, other, properties).isEmpty();
    }

    /**
     * @return why the conflicting stack named as given must not be deleted without replacing it, when deploying the
     * stack, eg "stack aws-full is running and already deploys Polaris at emryspolaris.click (AWS_POLARIS_DOMAIN_NAME)",
     * and how to delete it instead, or {@code null} if it may be, ie it is the stack itself
     */
    StackFilter.Refusal refusal(StackId stackId, String stackName, AfonPropertyProvider properties) {
        if (stackName.equals(stackId.toString())) {
            return null;
        }
        if (stackName.equals(stackId.cloud().toString())) {
            // deleting any stack of the cloud deletes the legacy stack too
            return new StackFilter.Refusal(String.format(
                "the legacy stack %s, deployed before each stack had its own tofu state, may be any stack", stackName),
                deleteCommand(stackId.toString()));
        }
        StackId other = StackId.parse(stackName);
        if (!exclusiveResources.containsKey(other)) {
            return new StackFilter.Refusal(String.format("stack %s is running, whose resources are unknown", stackName), null);
        }
        List<String> deploys = new ArrayList<>();
        List<String> holds = new ArrayList<>();
        for (AfonProperty resource : sharedResources(stackId, other, properties)) {
            String held = String.format("%s (%s)", properties.getValue(resource, ""), resource.getName());
            if (DEPLOYED_AT.containsKey(resource)) {
                deploys.add(DEPLOYED_AT.get(resource) + " at " + held);
            } else {
                holds.add("the pinned egress IP " + held);
            }
        }
        StringBuilder reason = new StringBuilder("stack " + stackName + " is running and already ");
        if (!deploys.isEmpty()) {
            reason.append("deploys ").append(String.join(" and ", deploys));
        }
        if (!holds.isEmpty()) {
            reason.append(deploys.isEmpty() ? "holds " : ", and holds ").append(String.join(" and ", holds));
        }
        return new StackFilter.Refusal(reason.toString(), deleteCommand(stackName));
    }

    private static String deleteCommand(String stackId) {
        return "afon-cli.sh manage-stack delete " + stackId;
    }

    /**
     * What is deployed at each domain name held by a stack, eg Polaris at {@code AWS_POLARIS_DOMAIN_NAME}.
     */
    private static final Map<AfonProperty, String> DEPLOYED_AT = Map.of(
        OIDC_DOMAIN_NAME, "KeyCloak",
        AWS_POLARIS_DOMAIN_NAME, "Polaris",
        AZURE_POLARIS_DOMAIN_NAME, "Polaris",
        GCP_POLARIS_DOMAIN_NAME, "Polaris",
        AWS_JUPYTER_DOMAIN_NAME, "Jupyter");

    /**
     * @return whether the stack uses the KeyCloak server another stack deploys, as it doesn't deploy its own
     * @param properties the config, eg of {@link AfonProperty#AWS_KEYCLOAK_EXTERNAL}
     */
    boolean usesExternalKeyCloak(StackId stackId, AfonPropertyProvider properties) {
        return !declaredResources(stackId, properties).contains(OIDC_DOMAIN_NAME);
    }

    private Set<AfonProperty> sharedResources(StackId stackId, StackId other, AfonPropertyProvider properties) {
        Set<AfonProperty> shared = heldResources(stackId, properties);
        shared.retainAll(heldResources(other, properties));
        return shared;
    }

    /**
     * @return the exclusive resources the stack holds, ie those it declares that are configured, eg not the pinned
     * egress IP, if {@link AfonProperty#AWS_EGRESS_IP} is not set, as the stack then gets its own
     */
    private Set<AfonProperty> heldResources(StackId stackId, AfonPropertyProvider properties) {
        Set<AfonProperty> resources = declaredResources(stackId, properties);
        resources.removeIf(resource -> properties.getValue(resource, "").isBlank());
        return resources;
    }

    /**
     * @return the exclusive resources the stack declares, without the KeyCloak domain name, if it uses the external
     * KeyCloak, rather than its own, as configured
     */
    private Set<AfonProperty> declaredResources(StackId stackId, AfonPropertyProvider properties) {
        Set<AfonProperty> resources = new TreeSet<>(exclusiveResources.getOrDefault(stackId, Set.of()));
        if (ownKeyCloakUnlessExternal.contains(stackId) && StackDefinitions.isKeyCloakExternal(properties)) {
            resources.remove(OIDC_DOMAIN_NAME);
        }
        return resources;
    }

    private void holds(StackId stackId, AfonProperty... resources) {
        exclusiveResources.put(stackId, Set.of(resources));
    }

    private void register(StackId stackId, StackAction action, StackSteps steps) {
        stackSteps.computeIfAbsent(stackId, id -> new EnumMap<>(StackAction.class)).put(action, steps);
    }

    /**
     * The steps deleting just the stack, and the legacy stack of its cloud, if still deployed, which may be any stack.
     */
    private StepsProvider deleteOnly(StackId stackId) {
        StackFilter filter = new StackFilter(
            String.format("stack %s and the legacy stack", stackId),
            name -> name.equals(stackId.toString()) || name.equals(stackId.cloud().toString()));
        return ops -> List.of(
            ops.deleteStacksIn(stackId, String.format("delete_%s_stack", stepName(stackId)), filter),
            ops.deleteComplete(stackId));
    }

    /**
     * The step deleting the stacks conflicting with the stack, before deploying it, which, unless replacing them,
     * fails if any is another stack than itself.
     */
    private Step deleteConflicting(StepOperations ops, boolean replace, StackId stackId) {
        AfonPropertyProvider properties = ops.properties();
        StackFilter filter = new StackFilter(
            "stacks conflicting with " + stackId,
            name -> conflicts(stackId, name, properties),
            name -> replace ? null : refusal(stackId, name, properties),
            stackId);
        return ops.deleteStacksIn(stackId, "delete_stacks_conflicting_with_" + stepName(stackId), filter);
    }

    /**
     * The steps before deploying the stack: deleting the stacks conflicting with it, then, if it uses the KeyCloak
     * another stack deploys, checking that it answers.
     */
    private List<Step> beforeDeploy(StepOperations ops, boolean replace, StackId stackId) {
        List<Step> steps = new ArrayList<>();
        steps.add(deleteConflicting(ops, replace, stackId));
        AfonPropertyProvider properties = ops.properties();
        if (usesExternalKeyCloak(stackId, properties)) {
            logger.info("Stack {} uses the KeyCloak at {}, which the aws-keycloak stack deploys{}", stackId, OIDC_DOMAIN_NAME,
                        ownKeyCloakUnlessExternal.contains(stackId) && StackDefinitions.isKeyCloakExternal(properties)
                        ? ", as configured by " + AWS_KEYCLOAK_EXTERNAL + "=true" : ", as it deploys none of its own");
            steps.add(ops.checkKeyCloakAnswers());
        }
        return steps;
    }

    private static String stepName(StackId stackId) {
        return StepOperations.stepName(stackId);
    }

    // The stack-specific steps are passed as functions, so that all the steps are created in the order they
    // run, as StepOperations logs as it creates them.

    private List<Step> deploy(StepOperations ops, boolean replace, StackId stackId,
                              Function<StepOperations, Step> deployStack,
                              Function<StepOperations, Step> waitForStack) {
        List<Step> steps = beforeDeploy(ops, replace, stackId);
        steps.add(deployStack.apply(ops));
        steps.add(waitForStack.apply(ops));
        steps.add(ops.deployComplete(stackId));
        return steps;
    }

    private List<Step> setupWithPolaris(StepOperations ops, boolean replace, StackId stackId,
                                        Function<StepOperations, Step> deployStack) {
        AfonCloud cloud = stackId.cloud();
        List<Step> steps = beforeDeploy(ops, replace, stackId);
        steps.add(deployStack.apply(ops));
        steps.add(ops.waitForPolarisAndKeycloakIn(cloud));
        steps.add(ops.configureOidcUsersFor(cloud));
        steps.add(ops.createAllCatalogsFor(cloud));
        steps.add(ops.configurePolarisUsersOnAllCatalogsFor(cloud));
        steps.add(ops.setupComplete(stackId));
        return steps;
    }

}
