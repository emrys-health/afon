package health.emrys.afon.docs;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.steps.StackAction;
import health.emrys.afon.config.StackId;
import health.emrys.afon.ops.steps.StackStepsFactory;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.List;
import java.util.stream.Collectors;

import static java.lang.String.format;

/**
 * Abstract base class for the documents of an action on a stack, run with {@code manage-stack <action> <stack-id>},
 * whose steps are provided by {@link StackStepsFactory}.
 */
public abstract class AbstractStackDocument extends AbstractDocument {

    private static final StackStepsFactory FACTORY = new StackStepsFactory();

    private final StepsProvider stepsProvider;
    /**
     * The {@code manage-stack} args, eg {@code setup aws-keycloak}.
     */
    private final String manageStackArgs;
    private final String note;

    /**
     * The document of the action on the stack, eg setup {@code aws-keycloak}.
     */
    protected AbstractStackDocument(AfonPropertyProvider properties, StackAction action, String stackId) {
        super(properties);
        StackId id = StackId.parse(stackId);
        this.stepsProvider = FACTORY.stepsProviderFor(action, id);
        if (stepsProvider == null) {
            throw new IllegalArgumentException(format("Unsupported action '%s' on stack '%s'", action, id));
        }
        this.manageStackArgs = action + " " + id;
        this.note = action == StackAction.DELETE ? "" : format(
            """

            If another running stack conflicts with `%s`, it fails before deleting any stack: add `--replace` to
            replace the conflicting stacks too.
            """, id);
    }

    /**
     * The document deleting every stack in the cloud, run with {@code delete <cloud>-all}, eg {@code delete aws-all}.
     */
    protected AbstractStackDocument(AfonPropertyProvider properties, AfonCloud cloud) {
        super(properties);
        List<String> stackIds = FACTORY.supportedActions().keySet().stream()
                                       .filter(id -> id.cloud() == cloud && !id.isAll())
                                       .map(StackId::toString)
                                       .toList();
        this.stepsProvider = FACTORY.deleteIn(cloud);
        this.manageStackArgs = StackAction.DELETE + " " + StackId.all(cloud);
        this.note = format(
            """

            To delete just one stack of cloud `%s`, give its stack id instead, ie one of %s.
            """, cloud, stackIds.stream().map(id -> "`" + id + "`").collect(Collectors.joining(", ")));
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return stepsProvider.stepsUsing(stepOperations);
    }

    @Override
    protected String howToRun() {
        return format(
                """

                To execute, run:
                ```shell
                afon-cli.sh manage-stack %s
                ```
                %s""", manageStackArgs, note);
    }

    @Override
    protected String howToResume(String lastStep) {
        return format(
                """
                If the job fails on a given step, you don't need to rerun the full list of steps.
                Instead, you can add to the manage-stack args the step from which to restart:

                `afon-cli.sh manage-stack %s <one-of-the-steps-from-list-above>`

                This will continue the execution starting at the chosen step.

                For example:
                ```shell
                afon-cli.sh manage-stack %s %s
                ```
                """,
                manageStackArgs,
                manageStackArgs,
                lastStep);
    }

}
