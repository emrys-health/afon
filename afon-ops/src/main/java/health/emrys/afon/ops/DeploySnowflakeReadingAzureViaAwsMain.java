package health.emrys.afon.ops;

import health.emrys.afon.ops.steps.*;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.AfonCloud.AZURE;
import static health.emrys.afon.ops.steps.StepsUtils.setupFullStackStepsUsing;

public class DeploySnowflakeReadingAzureViaAwsMain implements StepsProvider {

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        List<Step> steps = new ArrayList<>();
        steps.addAll(setupFullStackStepsUsing(stepOperations, AWS));
        steps.add(stepOperations.checkAwsSparkWritingTo(AZURE));
        steps.add(stepOperations.snowflakeReadingViaAwsPolaris(AZURE));
        return steps;
    }

    public static void main(String[] args) {
        new StepRunner().runUsing(new DeploySnowflakeReadingAzureViaAwsMain(), args);
    }

}
