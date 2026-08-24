package health.emrys.afon.ops;

import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;

import static health.emrys.afon.config.AfonCloud.AWS;

class CommonSteps {
    public static List<Step> awsStepsUsing(StepOperations stepOperations) {
        return
            List.of(
                stepOperations.deleteStack(AWS),
                stepOperations.deployFullStackIn(AWS),
                stepOperations.waitForPolarisIn(AWS),
                stepOperations.createAllCatalogsFor(AWS),
                stepOperations.checkAwsSparkWritingTo(AWS)
            );
    }
}
