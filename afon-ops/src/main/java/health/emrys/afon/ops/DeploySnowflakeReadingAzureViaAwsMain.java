package health.emrys.afon.ops;

import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepRunner;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.config.AfonCloud.AZURE;
import static health.emrys.afon.ops.CommonSteps.awsStepsUsing;

public class DeploySnowflakeReadingAzureViaAwsMain implements StepsProvider {

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        List<Step> steps = new ArrayList<>();
        steps.addAll(awsStepsUsing(stepOperations));
        steps.add(stepOperations.checkAwsSparkWritingTo(AZURE));
        steps.add(stepOperations.snowflakeReadingViaAwsPolaris(AZURE));
        return steps;
    }

    public static void main(String[] args) {
        new StepRunner().runUsing(new DeploySnowflakeReadingAzureViaAwsMain(), args);
    }

}
