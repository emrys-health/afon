package health.emrys.afon.ops.docs;

import health.emrys.afon.config.StackId;
import health.emrys.afon.ops.flow.StackFlows;
import health.emrys.afon.ops.steps.StackAction;

import java.util.Optional;

/**
 * The names of the generated doc files, which {@code manage-stack ... -h} finds from the args it was given.
 */
public final class DocumentNames {

    private DocumentNames() {
    }

    /**
     * @return the file of the doc of the action on the stack, eg {@code setup_aws_full_stack.md}, or
     * {@code delete_aws_stack.md} for {@code delete aws-all}
     */
    public static String stack(StackAction action, StackId stackId) {
        return action + "_" + (stackId.isAll() ? stackId.cloud().name().toLowerCase() : snake(stackId.toString())) + "_stack.md";
    }

    /**
     * @return the file of the doc of the flow, eg {@code flow_cross_cloud_azure.md}
     */
    public static String flow(String flowName) {
        return "flow_" + snake(flowName) + ".md";
    }

    /**
     * @return the file of the doc of the scenario of the stack, eg {@code run_aws_snowflake_reading_azure.md}
     */
    public static String scenario(StackId stackId, String scenario) {
        return "run_" + snake(stackId.toString()) + "_" + snake(scenario) + ".md";
    }

    /**
     * @param args the {@code manage-stack} args, without options, eg {@code setup aws-full}, {@code run cross-cloud-azure}
     *             or {@code run aws-snowflake reading-azure}, each with the step to resume from, if any
     * @return the file of the doc of the args, if they name an action on a stack, a flow or a scenario
     */
    public static Optional<String> forArgs(String[] args) {
        if (args.length < 2 || args.length > 4) {
            return Optional.empty();
        }
        StackAction action = StackAction.of(args[0]);
        if (action == null) {
            return Optional.empty();
        }
        if (action == StackAction.RUN) {
            if (args.length == 2 && StackFlows.find(args[1]).isPresent()) {
                return Optional.of(flow(args[1]));
            }
            return parse(args[1]).map(stackId -> scenario(stackId, args.length >= 3 ? args[2] : ""))
                                 .filter(name -> args.length >= 3);
        }
        // a step to resume from is not part of the doc's name
        return parse(args[1]).map(stackId -> stack(action,
            action == StackAction.DELETE && !stackId.isAll() ? StackId.all(stackId.cloud()) : stackId));
    }

    private static Optional<StackId> parse(String stackId) {
        try {
            return Optional.of(StackId.parse(stackId));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String snake(String name) {
        return name.toLowerCase().replace('-', '_');
    }

}
