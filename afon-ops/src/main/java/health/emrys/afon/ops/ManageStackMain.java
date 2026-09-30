package health.emrys.afon.ops;

import health.emrys.afon.ops.steps.StackAction;
import health.emrys.afon.config.StackId;
import health.emrys.afon.ops.steps.StackStepsFactory;
import health.emrys.afon.ops.steps.StepRunner;
import health.emrys.afon.ops.steps.StepsProvider;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Manages a stack, by running the steps of an action on it, as provided by {@link StackStepsFactory}:
 * <pre>
 * manage-stack &lt;action&gt; &lt;stack-id&gt; [&lt;step&gt;]
 * </pre>
 * <ul>
 *   <li>{@code <action>} is one of {@code delete}, {@code deploy} or {@code setup}, see {@link StackAction};</li>
 *   <li>{@code <stack-id>} is {@code <cloud>-<stack-type>}, eg {@code aws-keycloak}, see {@link StackId},
 *       or {@code <cloud>-all}, eg {@code aws-all}, to delete every stack of the cloud;</li>
 *   <li>{@code <step>}, if given, is the step from which to resume, as for the other stack commands.</li>
 * </ul>
 * Stacks run side by side unless they conflict, so deploying or setting up a stack first deletes the stacks it
 * conflicts with, while deleting a stack deletes just that stack, see {@link StackStepsFactory}.
 */
public class ManageStackMain {

    private static final Logger logger = getLogger(ManageStackMain.class);

    private final StackStepsFactory factory;

    ManageStackMain(StackStepsFactory factory) {
        this.factory = factory;
    }

    /**
     * @return the steps provider of the action on the stack, or {@code null} if the action or stack is unknown,
     * or the action is not supported for the stack
     */
    StepsProvider stepsProviderFor(String action, String stackId) {
        StackAction stackAction = StackAction.of(action);
        if (stackAction == null) {
            return null;
        }
        try {
            return factory.stepsProviderFor(stackAction, StackId.parse(stackId));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void printUsage() {
        System.out.println("Usage: manage-stack <action> <stack-id> [<step>], where:");
        System.out.println("  <action> is one of: " + Arrays.stream(StackAction.values())
                                                             .map(StackAction::toString)
                                                             .collect(Collectors.joining(", ")));
        System.out.println("  <stack-id> is one of, with its supported actions:");
        factory.supportedActions().forEach((stackId, actions) -> System.out.printf(
            "    %s: %s%n", stackId, actions.stream().map(StackAction::toString).collect(Collectors.joining(", "))));
        System.out.println("  <step>, if given, is the step from which to resume");
    }

    public static void main(String[] args) {
        ManageStackMain manager = new ManageStackMain(new StackStepsFactory());
        if (args.length < 2) {
            manager.printUsage();
            return;
        }
        StepsProvider stepsProvider = manager.stepsProviderFor(args[0], args[1]);
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
        logger.info("Running {} on stack {}", action, stackId);
        new StepRunner().runUsing(stepsProvider, Arrays.copyOfRange(args, 2, args.length));
    }

}
