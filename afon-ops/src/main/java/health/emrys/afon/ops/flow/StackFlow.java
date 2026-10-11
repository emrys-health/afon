package health.emrys.afon.ops.flow;

import health.emrys.afon.cdk.StackDefinitions;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.StackId;
import health.emrys.afon.ops.plan.StackPlanFactory;
import health.emrys.afon.ops.steps.StackAction;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * A named, ordered list of {@link FlowItem}s, run in order by {@code manage-stack run <name>}, so that what several
 * {@code manage-stack} commands do in the right order is one command, validated and documented as a whole.
 */
public record StackFlow(@NonNull String name, @NonNull List<FlowItem> items) {

    public StackFlow {
        if (name.isBlank() || items.isEmpty()) {
            throw new IllegalArgumentException("A flow needs a name and at least one item");
        }
        items = List.copyOf(items);
    }

    public static StackFlow of(String name, FlowItem... items) {
        return new StackFlow(name, List.of(items));
    }

    /**
     * Checks that the flow can run:
     * <ul>
     *   <li>every stack exists, and supports the action;</li>
     *   <li>every scenario exists on its stack;</li>
     *   <li>a scenario runs on a stack that no earlier item of the flow has deleted, without it having to set the stack up, so a
     *       flow may be just a chain of runs on stacks that are already set up;</li>
     *   <li>deploying or setting up a stack does not delete a stack that an earlier item of the flow left running, by
     *       conflicting with it, as the flow runs with {@code --replace}, or fails without it.</li>
     * </ul>
     *
     * @throws IllegalArgumentException naming the flow, the item and why it is not valid
     */
    public void validate(StackPlanFactory factory, AfonPropertyProvider properties) {
        List<StackId> running = new ArrayList<>();
        List<StackId> deleted = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            FlowItem item = items.get(i);
            StackId stackId = item.stackId();
            if (item.isRunnable()) {
                if (StackDefinitions.find(stackId).isEmpty()) {
                    throw invalid(i, "no such stack");
                }
                if (!factory.scenarios(stackId, properties).containsKey(item.scenario())) {
                    throw invalid(i, String.format("stack %s has no scenario '%s'", stackId, item.scenario()));
                }
                if (deleted.contains(stackId)) {
                    throw invalid(i, String.format("stack %s is deleted by an earlier item", stackId));
                }
                continue;
            }
            if (!factory.supportedActions().getOrDefault(stackId, List.of()).contains(item.action())) {
                throw invalid(i, String.format("unsupported action '%s' on stack '%s'", item.action(), stackId));
            }
            if (item.action() == StackAction.DELETE) {
                if (stackId.isAll()) {
                    running.removeIf(other -> other.cloud() == stackId.cloud());
                    deleted.addAll(factory.supportedActions().keySet().stream()
                                          .filter(other -> other.cloud() == stackId.cloud() && !other.isAll()).toList());
                } else {
                    running.remove(stackId);
                    deleted.add(stackId);
                }
                continue;
            }
            for (StackId other : running) {
                if (!other.equals(stackId) && factory.conflicts(stackId, other.toString(), properties)) {
                    throw invalid(i, String.format("it would delete %s, which an earlier item leaves running", other));
                }
            }
            running.remove(stackId);
            deleted.remove(stackId);
            running.add(stackId);
        }
    }

    private IllegalArgumentException invalid(int index, String reason) {
        return new IllegalArgumentException(String.format(
            "Flow '%s' item %d (%s) is not valid: %s", name, index + 1, items.get(index), reason));
    }
}
