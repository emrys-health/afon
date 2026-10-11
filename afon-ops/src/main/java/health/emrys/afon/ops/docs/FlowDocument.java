package health.emrys.afon.ops.docs;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.StackId;
import health.emrys.afon.ops.flow.FlowItem;
import health.emrys.afon.ops.steps.StackAction;
import health.emrys.afon.ops.flow.FlowPlanFactory;
import health.emrys.afon.ops.flow.FlowStep;
import health.emrys.afon.ops.flow.StackFlow;
import health.emrys.afon.ops.flow.StackFlows;
import health.emrys.afon.ops.plan.StackPlanFactory;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.lang.String.format;

/**
 * The document of a stack flow, run with {@code manage-stack run <flow>}, whose steps are those of
 * each of its items, grouped by item, as provided by {@link FlowPlanFactory}.
 */
public class FlowDocument extends AbstractDocument {

    private static final StackPlanFactory FACTORY = new StackPlanFactory();

    private final StackFlow flow;
    private final Map<Step, String> headings = new IdentityHashMap<>();

    public FlowDocument(AfonPropertyProvider properties, String flowName) {
        super(properties);
        this.flow = StackFlows.get(flowName, FACTORY, properties);
    }

    @Override
    public String getFilename() {
        return DocumentNames.flow(flow.name());
    }

    @Override
    public Set<AfonCloud> clouds() {
        return flow.items().stream().map(item -> item.stackId().cloud()).collect(Collectors.toSet());
    }

    @Override
    protected String getSupplementaryIntro() {
        List<String> needed = new ArrayList<>();
        Set<StackId> prepared = new HashSet<>();
        for (FlowItem item : flow.items()) {
            if (item.isRunnable()) {
                if (!prepared.contains(item.stackId()) && !needed.contains(item.stackId().toString())) {
                    needed.add(item.stackId().toString());
                }
            } else if (item.action() != StackAction.DELETE) {
                prepared.add(item.stackId());
            }
        }
        String intro = format("Runs, in order: %s.", flow.items().stream().map(item -> "`" + item.label() + "`")
                                                         .collect(Collectors.joining(", ")));
        return needed.isEmpty() ? intro : format("%s\n\nThe stacks %s must be set up first, with `manage-stack setup <stack-id>`.",
                                                 intro, needed.stream().map(id -> "`" + id + "`").collect(Collectors.joining(", ")));
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
            flow, new StepOperations(new DocumentingStepActions(java.io.OutputStream.nullOutputStream()),
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
