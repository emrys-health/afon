package health.emrys.afon.ops;

import health.emrys.afon.ops.steps.*;

import java.util.List;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.ops.steps.StepsUtils.setupFullStackStepsUsing;

public class SetupAwsFullStackMain implements StepsProvider {

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        return setupFullStackStepsUsing(stepOperations, AWS);
    }

    public static void main(String[] args) {
        new StepRunner().runUsing(new SetupAwsFullStackMain(), args);
    }

}
