package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.StackId;
import health.emrys.afon.ops.plan.StackPlanFactory;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;
import java.util.stream.Collectors;

import static java.lang.String.format;

/**
 * Abstract base class for the documents of a scenario of a stack, run with {@code manage-stack run <stack-id> <scenario>}
 * on the stack once it is set up, as well as the other stacks it needs, if any, whose steps are provided by
 * {@link StackPlanFactory}.
 */
public abstract class AbstractScenarioDocument extends AbstractDocument {

    private final StackPlanFactory factory = new StackPlanFactory();
    private final StackId stack;
    private final List<String> setups;
    private final String scenario;

    /**
     * The document of a scenario that needs just its own stack, eg {@code reading-azure} of {@code aws-snowflake}.
     */
    protected AbstractScenarioDocument(AfonPropertyProvider properties, String stackId, String scenario) {
        this(properties, stackId, List.of(stackId), scenario);
    }

    /**
     * @param stackId  the stack the scenario is run on, eg {@code aws-full}
     * @param setups   the stacks to set up first, in order, eg {@code aws-full} and {@code azure-polaris} for the scenario
     *                 {@code external-azure}, which needs the Azure Polaris too
     * @param scenario the scenario of the stack, eg {@code external-azure}
     */
    protected AbstractScenarioDocument(AfonPropertyProvider properties, String stackId, List<String> setups, String scenario) {
        super(properties);
        this.stack = StackId.parse(stackId);
        this.setups = setups;
        this.scenario = scenario;
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return factory.runSteps(stack, scenario, stepOperations);
    }

    @Override
    protected String howToRun() {
        String setupCommands = setups.stream().map(id -> "afon-cli.sh manage-stack setup " + id).collect(Collectors.joining("\n"));
        return format(
                """

                To execute, first set up the %s, unless already set up:
                ```shell
                %s
                ```
                If another running stack conflicts with %s, it fails before deleting any stack: add `--replace` to
                replace the conflicting stacks too.

                Then run the scenario, which is what the steps below are:
                ```shell
                afon-cli.sh manage-stack run %s %s
                ```
                """,
                setups.size() == 1 ? "stack" : "stacks, in this order", setupCommands,
                setups.size() == 1 ? "it" : "one of them", stack, scenario);
    }

    @Override
    protected String howToResume(String lastStep) {
        return format(
                """
                If the job fails on a given step, you don't need to rerun the full list of steps.
                Instead, you can add to the manage-stack args the step from which to restart:

                `afon-cli.sh manage-stack run %s %s <one-of-the-steps-from-list-above>`

                This will continue the execution starting at the chosen step.

                For example:
                ```shell
                afon-cli.sh manage-stack run %s %s %s
                ```
                """, stack, scenario, stack, scenario, lastStep);
    }
}
