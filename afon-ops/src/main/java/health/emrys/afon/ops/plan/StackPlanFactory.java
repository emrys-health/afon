package health.emrys.afon.ops.plan;

import health.emrys.afon.cdk.StackComponent;
import health.emrys.afon.cdk.StackDefinition;
import health.emrys.afon.cdk.StackDefinitions;
import health.emrys.afon.cdk.components.KeycloakComponent;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.StackId;
import health.emrys.afon.ops.plan.ComponentPlan.Endpoint;
import health.emrys.afon.ops.steps.StackAction;
import health.emrys.afon.ops.steps.StackFilter;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.AfonProperty.AWS_EGRESS_IP;

/**
 * Provides the steps for each action on each stack, derived from the {@link StackDefinition} of the stack and
 * the {@link ComponentPlan} associated to the components it is composed of,
 * so that every stack defined in {@link StackDefinitions} can be run by {@code manage-stack}.
 * <p>
 * The stack is deployed as a whole, by {@link StepOperations#deployStack}, which applies the plan of all its components,
 * so the steps derived from the plans of each component are combined, based on the {@link StackAction}:
 * <ul>
 *   <li>{@code delete}: just the stack, and the legacy stack of its cloud; or every stack of the cloud for {@code <cloud>-all};</li>
 *   <li>{@code deploy}: delete the stacks conflicting with it, check the KeyCloak it uses if it has none of its own, deploy it,
 *       after the certificates its components need are issued, wait for what its components need, then report it complete;</li>
 *   <li>{@code setup}: as deploy, then what each component configures, in the order of the components, then report it complete.</li>
 * </ul>
 * <p>
 * Stacks run side by side, unless they conflict, ie they hold a common exclusive resource, such as the DNS record of a domain
 * name, or the pinned egress IP, which only one running stack can hold, see {@link #heldResources}.  So deploying or setting up a
 * stack first deletes the stacks it conflicts with, eg deploying {@code aws-polaris} deletes {@code aws-full}, which also holds the
 * Polaris domain name, but not {@code aws-keycloak}.  It only deletes the stack itself, if running, unless told to replace the
 * other conflicting stacks too, else it fails, naming them, before deleting any.
 * Deleting a stack deletes just that stack, and deleting {@code <cloud>-all}, eg {@code aws-all}, deletes every stack of the cloud.
 */
public class StackPlanFactory {

    private final ComponentPlanRegistry registry;

    public StackPlanFactory() {
        this(ComponentPlanRegistry.standard());
    }

    public StackPlanFactory(ComponentPlanRegistry registry) {
        this.registry = registry;
    }

    /**
     * @param replace whether deploying or setting up the stack replaces the other stacks conflicting with it, else it fails,
     *                naming them, before deleting any
     * @return the steps provider of the action on the stack, or {@code null} if there is no such stack, or the action is not
     * supported for it
     */
    public StepsProvider stepsProviderFor(StackAction action, StackId stackId, boolean replace) {
        if (!supports(action, stackId)) {
            return null;
        }
        return ops -> steps(action, stackId, ops, replace);
    }

    /**
     * @return the actions supported by each stack, in the order of {@link StackAction}: every stack of
     * {@link StackDefinitions}, and {@code <cloud>-all}, which only deletes
     */
    public SortedMap<StackId, List<StackAction>> supportedActions() {
        SortedMap<StackId, List<StackAction>> supported = new TreeMap<>();
        StackDefinitions.all().forEach(definition -> supported.put(definition.id(), Arrays.asList(StackAction.values())));
        Arrays.stream(AfonCloud.values()).forEach(cloud -> supported.put(StackId.all(cloud), List.of(StackAction.DELETE)));
        return supported;
    }

    private boolean supports(StackAction action, StackId stackId) {
        List<StackAction> actions = supportedActions().get(stackId);
        return actions != null && actions.contains(action);
    }

    /**
     * @throws IllegalArgumentException if there is no such stack, or the action is not supported for it
     */
    public List<Step> steps(StackAction action, StackId stackId, StepOperations ops, boolean replace) {
        if (!supports(action, stackId)) {
            throw new IllegalArgumentException(String.format("Unsupported action '%s' on stack '%s'", action, stackId));
        }
        if (stackId.isAll()) {
            return List.of(ops.deleteStackIn(stackId.cloud()), ops.deleteComplete(stackId));
        }
        List<StackComponent> components = StackDefinitions.get(stackId).componentsFor(ops.properties());
        return switch (action) {
            case DELETE -> delete(stackId, ops);
            case DEPLOY -> deploy(stackId, components, ops, replace, false);
            case SETUP -> deploy(stackId, components, ops, replace, true);
        };
    }

    private List<Step> delete(StackId stackId, StepOperations ops) {
        StackFilter filter = new StackFilter(
            String.format("stack %s and the legacy stack", stackId),
            name -> name.equals(stackId.toString()) || name.equals(stackId.cloud().toString()));
        return List.of(
            ops.deleteStacksIn(stackId, String.format("delete_%s_stack", stepName(stackId)), filter),
            ops.deleteComplete(stackId));
    }

    // The steps are created in the order they run, as StepOperations logs as it creates them.
    private List<Step> deploy(StackId stackId, List<StackComponent> components, StepOperations ops, boolean replace,
                              boolean setup) {
        AfonCloud cloud = stackId.cloud();
        List<Step> steps = new ArrayList<>();
        steps.add(deleteConflicting(stackId, ops, replace));
        // the KeyCloak the stack uses is another stack's, if it has none of its own: it must answer
        if (components.stream().noneMatch(KeycloakComponent.class::isInstance)) {
            steps.add(ops.checkKeyCloakAnswers());
        }
        steps.add(ops.deployStack(stackId, certificates(components, cloud)));
        steps.add(wait(components, ops, cloud));
        steps.add(ops.deployComplete(stackId));
        if (setup) {
            // in the order of the components, the first to ask for a step runs it
            Map<String, Step> setupSteps = new LinkedHashMap<>();
            for (StackComponent component : components) {
                for (Step step : registry.of(component).setupSteps(ops, cloud)) {
                    setupSteps.putIfAbsent(step.getName(), step);
                }
            }
            steps.addAll(setupSteps.values());
            steps.add(ops.setupComplete(stackId));
        }
        return steps;
    }

    /**
     * @return the properties of the certificates of the components' load balancers, which are issued before deploying the stack
     */
    private List<AfonProperty> certificates(List<StackComponent> components, AfonCloud cloud) {
        Set<AfonProperty> certificates = new LinkedHashSet<>();
        components.forEach(component -> certificates.addAll(registry.of(component).certificates(cloud)));
        return List.copyOf(certificates);
    }

    /**
     * The step waiting for what the components of the stack expose: the union of what each needs.
     */
    private Step wait(List<StackComponent> components, StepOperations ops, AfonCloud cloud) {
        Set<Endpoint> endpoints = EnumSet.noneOf(Endpoint.class);
        components.forEach(component -> endpoints.addAll(registry.of(component).waitsFor()));
        if (endpoints.contains(Endpoint.POLARIS)) {
            return ops.waitForPolarisAndKeycloakIn(cloud);
        }
        return ops.waitForKeyCloak();
    }

    /**
     * The step deleting the stacks conflicting with the stack, before deploying it, which, unless replacing them, fails
     * if any is another stack than itself.
     */
    private Step deleteConflicting(StackId stackId, StepOperations ops, boolean replace) {
        AfonPropertyProvider properties = ops.properties();
        StackFilter filter = new StackFilter(
            "stacks conflicting with " + stackId,
            name -> conflicts(stackId, name, properties),
            name -> replace ? null : refusal(stackId, name, properties),
            stackId);
        return ops.deleteStacksIn(stackId, "delete_stacks_conflicting_with_" + stepName(stackId), filter);
    }

    /**
     * @return why the conflicting stack named as given must not be deleted without replacing it, when deploying the
     * stack, eg "stack aws-full is running and already holds the Polaris domain name emryspolaris.click (AWS_POLARIS_DOMAIN_NAME)",
     * and how to delete it instead, or {@code null} if it may be, ie it is the stack itself, or holds nothing in common with it
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
        if (StackDefinitions.find(other).isEmpty()) {
            return new StackFilter.Refusal(String.format("stack %s is running, whose resources are unknown", stackName), null);
        }
        Set<AfonProperty> shared = heldResources(stackId, properties);
        shared.retainAll(heldResources(other, properties));
        if (shared.isEmpty()) {
            return null; // nothing in common, so no reason to keep it
        }
        // what the other stack deploys at each domain name it holds
        Map<AfonProperty, String> deployed = new LinkedHashMap<>();
        for (StackComponent component : StackDefinitions.get(other).componentsFor(properties)) {
            ComponentPlan componentSteps = registry.of(component);
            componentSteps.domains(other.cloud()).forEach(domain -> deployed.put(domain, componentSteps.label()));
        }
        List<String> deploys = new ArrayList<>();
        List<String> holds = new ArrayList<>();
        for (AfonProperty resource : shared) {
            String held = String.format("%s (%s)", properties.getValue(resource, ""), resource.getName());
            if (deployed.containsKey(resource)) {
                deploys.add(deployed.get(resource) + " at " + held);
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
     * @return whether the stack deployed from a stack dir or tofu state named as given, eg {@code aws-full}, conflicts with
     * the stack, ie it is the same stack, or they hold a common exclusive resource.  The legacy stack, named as the cloud,
     * eg {@code aws}, and a stack that is not defined, may hold any resource, so conflict with every stack.
     */
    public boolean conflicts(StackId stackId, String stackName, AfonPropertyProvider properties) {
        if (stackName.equals(stackId.cloud().toString())) {
            return true;
        }
        StackId other = StackId.parse(stackName);
        if (other.equals(stackId) || StackDefinitions.find(other).isEmpty()) {
            return true;
        }
        Set<AfonProperty> shared = heldResources(stackId, properties);
        shared.retainAll(heldResources(other, properties));
        return !shared.isEmpty();
    }

    /**
     * @return the exclusive resources the stack holds: those of its components, eg the domain name of its Polaris, and the
     * pinned egress IP on AWS if it pins it, ie the ones that are configured, as a stack without a pinned egress IP gets its own
     */
    public Set<AfonProperty> heldResources(StackId stackId, AfonPropertyProvider properties) {
        StackDefinition definition = StackDefinitions.get(stackId);
        Set<AfonProperty> resources = new TreeSet<>();
        for (StackComponent component : definition.componentsFor(properties)) {
            resources.addAll(registry.of(component).domains(stackId.cloud()));
        }
        if (stackId.cloud() == AWS && definition.pinnedEgressIp()) {
            resources.add(AWS_EGRESS_IP);
        }
        resources.removeIf(resource -> properties.getValue(resource, "").isBlank());
        return resources;
    }

    private static String stepName(StackId stackId) {
        return stackId.toString().replace('-', '_');
    }
}
