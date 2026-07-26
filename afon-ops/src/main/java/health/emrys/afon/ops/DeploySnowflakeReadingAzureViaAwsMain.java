package health.emrys.afon.ops;

import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepRunner;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.ArrayList;
import java.util.List;

public class DeploySnowflakeReadingAzureViaAwsMain implements StepsProvider {

    public static List<Step> steps(StepOperations stepOperations) {
        List<Step> steps = new ArrayList<>();
        steps.addAll(DeployFullStackAwsMain.steps(stepOperations));
        steps.add(stepOperations.checkAwsSparkConnectorReadingAzure());
        steps.add(stepOperations.snowflakeReadsAzureViaPolaris());
        return steps;
    }

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        return steps(stepOperations);
    }

    public static void main(String[] args) {
        StepRunner app = new StepRunner();
        app.run(new DeploySnowflakeReadingAzureViaAwsMain(), args);
    }

}
