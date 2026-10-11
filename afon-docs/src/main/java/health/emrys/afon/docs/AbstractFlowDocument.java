package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.flow.FlowPlanFactory;
import health.emrys.afon.ops.flow.FlowStep;
import health.emrys.afon.ops.flow.StackFlow;
import health.emrys.afon.ops.flow.StackFlows;
import health.emrys.afon.ops.plan.StackPlanFactory;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static java.lang.String.format;

/**
 * Abstract base class for the documents of a stack flow, run with {@code manage-stack run <name>}, whose steps are those of
 * each of its items, grouped by item, as provided by {@link FlowPlanFactory}.
 */
public abstract class AbstractFlowDocument extends AbstractDocument {

    private static final StackPlanFactory FACTORY = new StackPlanFactory();

    private final StackFlow flow;
    private final Map<Step, String> headings = new IdentityHashMap<>();

    protected AbstractFlowDocument(AfonPropertyProvider properties, String flowName) {
        super(properties);
        this.flow = StackFlows.get(flowName, FACTORY, properties);
    }

    /**
     * @return the name of the flow documented
     */
    public String flowName() {
        return flow.name();
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        List<FlowStep> steps = new FlowPlanFactory(FACTORY).steps(flow, stepOperations, false);
        int current = 0;
        for (FlowStep step : steps) {
            if (step.itemIndex() != current) {
                current = step.itemIndex();
                headings.put(step.step(), format("%d. %s", current, step.item().label()));
            }
        }
        return steps.stream().map(FlowStep::step).toList();
    }

    @Override
    public String sectionHeading(Step step) {
        return headings.get(step);
    }

    @Override
    protected String stepsBeingRun() {
        StringBuilder out = new StringBuilder("The steps being run are:\n");
        List<FlowStep> steps = new FlowPlanFactory(FACTORY).steps(
            flow, new StepOperations(new health.emrys.afon.ops.steps.StepDocumentation(java.io.OutputStream.nullOutputStream()),
                                     properties), false);
        int current = 0;
        for (FlowStep step : steps) {
            if (step.itemIndex() != current) {
                current = step.itemIndex();
                out.append(format("%n%d. `%s`%n", current, step.item().label()));
            }
            out.append(format("    - %s%n", step.qualifiedName()));
        }
        out.append('\n').append(howToResume(steps.get(steps.size() - 1).qualifiedName()));
        return out.toString();
    }

    @Override
    protected String howToRun() {
        return format(
            """

            To execute, run:
            ```shell
            afon-cli.sh manage-stack run %s
            ```

            To list the steps without running them, add `--dry-run`.  If a stack of the flow conflicts with another running
            stack, it fails before deleting any stack: add `--replace` to replace the conflicting stacks too.
            """, flow.name());
    }

    @Override
    protected String howToResume(String lastStep) {
        return format(
            """
            If the flow fails on a step, it stops, reporting the items that did not run and the step to resume from.
            You don't need to rerun the full flow.  Instead, add to the command the `<item>:<step>` from which to restart:

            `afon-cli.sh manage-stack run %s <one-of-the-steps-from-list-above>`

            This will continue the execution starting at the chosen step, and with the items after it.

            For example:
            ```shell
            afon-cli.sh manage-stack run %s %s
            ```
            """, flow.name(), flow.name(), lastStep);
    }
}
