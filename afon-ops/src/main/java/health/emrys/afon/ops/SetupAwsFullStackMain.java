package health.emrys.afon.ops;

import health.emrys.afon.ops.steps.StackId;
import health.emrys.afon.ops.steps.StackStepsFactory;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepRunner;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.List;

import static health.emrys.afon.ops.steps.StackAction.SETUP;

/**
 * Equivalent to {@code manage-stack setup aws-full}, whose steps are defined by {@link StackStepsFactory}.
 */
public class SetupAwsFullStackMain implements StepsProvider {

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        return new StackStepsFactory().steps(SETUP, StackId.parse("aws-full"), stepOperations);
    }

    public static void main(String[] args) {
        new StepRunner().runUsing(new SetupAwsFullStackMain(), args);
    }

}
