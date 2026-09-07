package health.emrys.afon.ops;

import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepRunner;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.ops.steps.StepsUtils.setupFullStackStepsUsing;

public class DeploySnowflakeReadingAwsViaAwsMain implements StepsProvider {

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        List<Step> steps = new ArrayList<>();
        steps.addAll(setupFullStackStepsUsing(stepOperations, AWS));
        steps.add(stepOperations.snowflakeReadingViaAwsPolaris(stepOperations.afonCatalogs().aws()));
        return steps;
    }

    public static void main(String[] args) {
        new StepRunner().runUsing(new DeploySnowflakeReadingAwsViaAwsMain(), args);
    }

}
