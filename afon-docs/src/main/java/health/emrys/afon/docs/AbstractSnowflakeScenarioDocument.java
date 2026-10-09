package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.StackId;
import health.emrys.afon.ops.plan.StackPlanFactory;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;

import static java.lang.String.format;

/**
 * Abstract base class for the documents of a Snowflake scenario of the {@code aws-snowflake} stack, run with
 * {@code manage-stack run aws-snowflake <scenario>} on the stack once it is set up, whose steps are provided by
 * {@link StackPlanFactory}.
 */
public abstract class AbstractSnowflakeScenarioDocument extends AbstractDocument {

    private static final StackId STACK = StackId.parse("aws-snowflake");

    private final StackPlanFactory factory = new StackPlanFactory();
    private final String scenario;

    /**
     * @param scenario the scenario of the stack, eg {@code reading-azure}
     */
    protected AbstractSnowflakeScenarioDocument(AfonPropertyProvider properties, String scenario) {
        super(properties);
        this.scenario = scenario;
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return factory.runSteps(STACK, scenario, stepOperations);
    }

    @Override
    protected String howToRun() {
        return format(
                """

                To execute, first set up the stack, unless it is already set up:
                ```shell
                afon-cli.sh manage-stack setup %s
                ```
                If another running stack conflicts with `%s`, it fails before deleting any stack: add `--replace` to
                replace the conflicting stacks too.

                Then run the scenario, which is what the steps below are:
                ```shell
                afon-cli.sh manage-stack run %s %s
                ```
                """, STACK, STACK, STACK, scenario);
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
                """, STACK, scenario, STACK, scenario, lastStep);
    }
}
