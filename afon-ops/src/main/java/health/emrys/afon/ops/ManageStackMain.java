package health.emrys.afon.ops;

import health.emrys.afon.config.StackId;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.ops.plan.StackPlanFactory;
import health.emrys.afon.ops.steps.StackAction;
import health.emrys.afon.ops.steps.StepRunner;
import health.emrys.afon.ops.steps.StepsProvider;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Manages a stack, by running the steps of an action on it, as provided by {@link StackPlanFactory}:
 * <pre>
 * manage-stack &lt;action&gt; &lt;stack-id&gt; [&lt;step&gt;] [--replace]
 * manage-stack run &lt;stack-id&gt; [&lt;scenario&gt; [&lt;step&gt;]]
 * </pre>
 * <ul>
 *   <li>{@code <action>} is one of {@code delete}, {@code deploy} or {@code setup}, see {@link StackAction};</li>
 *   <li>{@code <stack-id>} is {@code <cloud>-<stack-type>}, eg {@code aws-keycloak}, see {@link StackId},
 *       or {@code <cloud>-all}, eg {@code aws-all}, to delete every stack of the cloud;</li>
 *   <li>{@code <step>}, if given, is the step from which to resume, as for the other stack commands;</li>
 *   <li>{@code --replace}, if given, lets {@code deploy} and {@code setup} delete the other stacks conflicting with the
 *       stack, else they fail, naming them, before deleting any.</li>
 * </ul>
 * {@code run} runs a scenario on a stack that is set up, as provided by its components, eg Snowflake reading a catalog, and without
 * a scenario lists those of the stack.
 * <p>
 * Stacks run side by side unless they conflict, so deploying or setting up a stack first deletes the stacks it
 * conflicts with, while deleting a stack deletes just that stack, see {@link StackPlanFactory}.
 */
public class ManageStackMain {

    private static final Logger logger = getLogger(ManageStackMain.class);

    /**
     * The option letting deploy and setup delete the other stacks conflicting with the stack.
     */
    static final String REPLACE = "--replace";

    /**
     * The verb running a scenario on a stack, which is not a {@link StackAction}.
     */
    static final String RUN = "run";

    private final StackPlanFactory factory;

    ManageStackMain(StackPlanFactory factory) {
        this.factory = factory;
    }

    /**
     * @return the steps provider of the action on the stack, or {@code null} if the action or stack is unknown,
     * or the action is not supported for the stack
     */
    StepsProvider stepsProviderFor(String action, String stackId) {
        return stepsProviderFor(action, stackId, false);
    }

    /**
     * @return the steps provider of the action on the stack, replacing the other conflicting stacks or not, or
     * {@code null} if the action or stack is unknown, or the action is not supported for the stack
     */
    StepsProvider stepsProviderFor(String action, String stackId, boolean replace) {
        StackAction stackAction = StackAction.of(action);
        if (stackAction == null) {
            return null;
        }
        try {
            return factory.stepsProviderFor(stackAction, StackId.parse(stackId), replace);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * @return the steps provider of the scenario of the stack, or {@code null} if the stack is unknown, or has no such scenario
     */
    StepsProvider runProviderFor(String stackId, String scenario, AfonPropertyProvider properties) {
        try {
            return factory.scenarios(StackId.parse(stackId), properties).get(scenario);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void printScenarios(String stackId, AfonPropertyProvider properties) {
        Map<String, StepsProvider> scenarios;
        try {
            scenarios = factory.scenarios(StackId.parse(stackId), properties);
        } catch (IllegalArgumentException e) {
            scenarios = Map.of();
        }
        System.out.printf("Scenarios of stack '%s': %s%n", stackId, scenarios.isEmpty() ? "none" : String.join(", ", scenarios.keySet()));
    }

    private void printUsage() {
        System.out.println("Usage: manage-stack <action> <stack-id> [<step>] [" + REPLACE + "], where:");
        System.out.println("  <action> is one of: " + Arrays.stream(StackAction.values())
                                                             .map(StackAction::toString)
                                                             .collect(Collectors.joining(", ")));
        System.out.println("  <stack-id> is one of, with its supported actions:");
        factory.supportedActions().forEach((stackId, actions) -> System.out.printf(
            "    %s: %s%n", stackId, actions.stream().map(StackAction::toString).collect(Collectors.joining(", "))));
        System.out.println("  <step>, if given, is the step from which to resume");
        System.out.println("Usage: manage-stack run <stack-id> [<scenario> [<step>]], which runs a scenario on a stack that is set up,");
        System.out.println("  or lists the scenarios of the stack, if none is given");
        AfonPropertyProvider properties = new OpsPropertyProvider();
        factory.supportedActions().keySet().stream().filter(stackId -> !stackId.isAll())
               .filter(stackId -> !factory.scenarios(stackId, properties).isEmpty())
               .forEach(stackId -> printScenarios(stackId.toString(), properties));
        System.out.println("  " + REPLACE + ", if given, lets deploy and setup delete the other stacks conflicting with the stack,");
        System.out.println("    else they fail, naming them, before deleting any");
    }

    public static void main(String[] allArgs) {
        ManageStackMain manager = new ManageStackMain(new StackPlanFactory());
        boolean replace = Arrays.asList(allArgs).contains(REPLACE);
        String[] args = Arrays.stream(allArgs).filter(arg -> !arg.equals(REPLACE)).toArray(String[]::new);
        if (args.length < 2) {
            manager.printUsage();
            return;
        }
        if (args[0].equalsIgnoreCase(RUN)) {
            manager.run(args);
            return;
        }
        StepsProvider stepsProvider = manager.stepsProviderFor(args[0], args[1], replace);
        if (stepsProvider == null) {
            System.out.printf("Unsupported action '%s' on stack '%s'%n", args[0], args[1]);
            manager.printUsage();
            System.exit(1);
            return;
        }
        StackAction action = StackAction.of(args[0]);
        StackId stackId = StackId.parse(args[1]);
        if (action == StackAction.DELETE && stackId.isAll()) {
            // printed as well as logged, as the console logging may be off, eg in the CLI
            String warning = String.format("Deleting %s deletes every stack deployed in cloud %s", stackId, stackId.cloud());
            System.out.println(warning);
            logger.warn(warning);
        }
        logger.info("Running {} on stack {}{}", action, stackId, replace ? ", replacing the conflicting stacks" : "");
        new StepRunner().runUsing(stepsProvider, Arrays.copyOfRange(args, 2, args.length));
    }

    private void run(String[] args) {
        AfonPropertyProvider properties = new OpsPropertyProvider();
        if (args.length < 3) {
            printScenarios(args[1], properties);
            return;
        }
        StepsProvider stepsProvider = runProviderFor(args[1], args[2], properties);
        if (stepsProvider == null) {
            System.out.printf("Unsupported scenario '%s' on stack '%s'%n", args[2], args[1]);
            printScenarios(args[1], properties);
            System.exit(1);
            return;
        }
        logger.info("Running scenario {} on stack {}", args[2], args[1]);
        new StepRunner().runUsing(stepsProvider, Arrays.copyOfRange(args, 3, args.length));
    }

}
