package health.emrys.afon.ops;

import health.emrys.afon.config.StackId;
import health.emrys.afon.ops.steps.StackStepsFactory;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepRunner;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.List;

import static health.emrys.afon.ops.steps.StackAction.DEPLOY;

/**
 * Equivalent to {@code manage-stack deploy azure-polaris}, whose steps are defined by {@link StackStepsFactory}.
 */
public class DeployAzurePolarisStackMain implements StepsProvider {

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        return new StackStepsFactory().steps(DEPLOY, StackId.parse("azure-polaris"), stepOperations);
    }

    public static void main(String[] args) {
        new StepRunner().runUsing(new DeployAzurePolarisStackMain(), args);
    }

}
