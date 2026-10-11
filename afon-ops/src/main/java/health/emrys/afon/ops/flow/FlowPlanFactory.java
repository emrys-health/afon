package health.emrys.afon.ops.flow;

import health.emrys.afon.ops.plan.StackPlanFactory;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides the steps of a {@link StackFlow}: those of each item, as {@link StackPlanFactory} derives them, in the order of
 * the items.
 */
public class FlowPlanFactory {

    private final StackPlanFactory factory;

    public FlowPlanFactory(StackPlanFactory factory) {
        this.factory = factory;
    }

    /**
     * @param replace whether every deploy or setup item replaces the other stacks conflicting with it
     * @throws IllegalArgumentException if the flow is not valid
     */
    public List<FlowStep> steps(StackFlow flow, StepOperations ops, boolean replace) {
        flow.validate(factory, ops.properties());
        List<FlowStep> steps = new ArrayList<>();
        for (int i = 0; i < flow.items().size(); i++) {
            FlowItem item = flow.items().get(i);
            List<Step> itemSteps = item.isRunnable()
                ? factory.scenarioSteps(item.stackId(), item.scenario(), ops)
                : factory.steps(item.action(), item.stackId(), ops, replace);
            for (Step step : itemSteps) {
                steps.add(new FlowStep(i + 1, item, step));
            }
        }
        return steps;
    }
}
