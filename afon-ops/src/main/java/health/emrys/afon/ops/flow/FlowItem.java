package health.emrys.afon.ops.flow;

import health.emrys.afon.config.StackId;
import health.emrys.afon.ops.steps.StackAction;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * An item of a {@link StackFlow}: either an action on a stack, eg {@code setup aws-full}, or a scenario run on a stack,
 * eg {@code run aws-full external-azure}, as {@code manage-stack} runs them.
 *
 * @param action   the action on the stack: {@code delete}, {@code deploy}, {@code setup} or {@code run}
 * @param stackId  the stack acted on
 * @param scenario the scenario run on the stack, or {@code null} for an action
 */
public record FlowItem(@NonNull StackAction action, @NonNull StackId stackId, @Nullable String scenario) {

    public static FlowItem delete(String stackId) {
        return new FlowItem(StackAction.DELETE, StackId.parse(stackId), null);
    }

    public static FlowItem deploy(String stackId) {
        return new FlowItem(StackAction.DEPLOY, StackId.parse(stackId), null);
    }

    public static FlowItem setup(String stackId) {
        return new FlowItem(StackAction.SETUP, StackId.parse(stackId), null);
    }

    public static FlowItem run(String stackId, String scenario) {
        return new FlowItem(StackAction.RUN, StackId.parse(stackId), scenario);
    }

    public boolean isRunnable() {
        return action == StackAction.RUN;
    }

    /**
     * @return the item as the {@code manage-stack} args, eg {@code setup aws-full} or {@code run aws-full external-azure}
     */
    public String label() {
        return isRunnable() ? String.format("run %s %s", stackId, scenario) : String.format("%s %s", action, stackId);
    }

    @Override
    public String toString() {
        return label();
    }
}
