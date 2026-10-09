package health.emrys.afon.ops.plan;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import health.emrys.afon.ops.steps.StepsProvider;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The component plan represents a decorator around its deployment, for each kind of component:
 * the operational side of it, what the commands on a stack ({@code manage-stack}) need to know and do around
 * the component, when a stack has it.
 * <p>
 * It does <b>not</b> deploy the component.  What a stack deploys is the components defined in
 * {@link health.emrys.afon.cdk.StackDefinitions}, and the stack is deployed as a whole, by the single step
 * ({@link StepOperations#deployStack}), which applies the plan of all its components at once.
 * The plan only adds, for each component the stack has:
 * <ul>
 *   <li>{@link #domains}: the exclusive resources it holds, which decide which stacks conflict;</li>
 *   <li>{@link #certificates}: the certificates to be issued before the stack is deployed;</li>
 *   <li>{@link #waitsFor}: what to wait for once the stack is deployed;</li>
 *   <li>{@link #setupSteps}: what to configure when setting the stack up;</li>
 *   <li>{@link #runSteps}: the scenarios it can be asked to run on a stack that is set up, by {@code manage-stack run};</li>
 *   <li>{@link #label}: how it is called in messages.</li>
 * </ul>
 * Each is optional and the defaults bring nothing, which is right for components that are only infrastructure, eg the network
 * policies, see {@link InfrastructurePlan}.  The contributions of the components of a stack are combined by {@link StackPlanFactory}:
 * the held resources, certificates and waits are the union of what each brings, and the setup steps run in the order of the
 * components, a step asked for by more than one running once.  Every component must be declared in
 * {@link ComponentPlanRegistry}, even if it brings nothing.
 */
public interface ComponentPlan {

    /**
     * @return the cloud's property of the logical name, or none if the cloud has none, eg the certificates of the ALBs, which
     * only AWS has, so that a component can name them without asking which cloud it is
     */
    static List<AfonProperty> ofCloud(AfonCloud cloud, String logicalName) {
        return AfonProperty.forCloud(cloud, logicalName).map(List::of).orElse(List.of());
    }

    /**
     * The endpoint a stack is waited for on, once deployed, which is when its DNS record has propagated.
     */
    enum Endpoint {KEYCLOAK, POLARIS}

    /**
     * @return what the component is called in messages, eg "Polaris" in "stack aws-full is running and already deploys Polaris at
     * polaris.example (AWS_POLARIS_DOMAIN_NAME)", which explains why a stack cannot be deployed beside another
     */
    default String label() {
        return getClass().getSimpleName();
    }

    /**
     * @return the properties of the domain names the component holds exclusively on the cloud, ie that only one running stack can
     * serve, eg the one of the Polaris DNS record.  Stacks holding the same one conflict, so deploying one first deletes the other.
     * None by default, ie it does not stop other stacks from running beside it.
     */
    default List<AfonProperty> domains(AfonCloud cloud) {
        return List.of();
    }

    /**
     * @return the properties of the certificates the component's HTTPS endpoint needs on the cloud, which must be issued
     * before the stack is deployed, eg the ACM certificate ARN of the ALB of the Polaris DNS record on AWS.  None by default, and
     * on the clouds that issue them in the cluster, ie that have no such property, see {@link #ofCloud}.
     */
    default List<AfonProperty> certificates(AfonCloud cloud) {
        return List.of();
    }

    /**
     * @return what is waited for after the stack is deployed, eg that the Polaris domain name resolves.  The stack waits for the union
     * of what its components need.  Nothing by default.
     */
    default Set<Endpoint> waitsFor() {
        return Set.of();
    }

    /**
     * @return the steps configuring what the stack deployed for the component, when setting the stack up, ie after it is deployed and
     * waited for.  A step that more than one component asks for, eg configuring the OIDC users, which both KeyCloak and Polaris need,
     * runs once, where first asked.  None by default.
     */
    default List<Step> setupSteps(StepOperations ops, AfonCloud cloud) {
        return List.of();
    }

    /**
     * @return the scenarios the component can run on a stack that is set up, by name, in the order they are listed, eg
     * {@code reading-azure} for Snowflake reading the Azure catalog, run by {@code manage-stack run <stack-id> <scenario>}.
     * Unlike the setup steps they are not part of setting the stack up, as they differ in what they exercise, and depend on
     * more than the stack, eg another cloud.  The steps are created when the scenario is run, not when it is listed.
     * A scenario may span components, eg a Spark write followed by a Snowflake read.  None by default.
     */
    default Map<String, StepsProvider> runSteps(AfonCloud cloud) {
        return Map.of();
    }
}
