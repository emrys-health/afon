package health.emrys.afon.demo;

import health.emrys.afon.steps.Step;
import health.emrys.afon.steps.StepOperations;
import health.emrys.afon.steps.StepRunner;
import health.emrys.afon.steps.StepsProvider;

import java.util.ArrayList;
import java.util.List;

public class DeploySnowflakeReadingAwsViaAwsMain implements StepsProvider {

    public static List<Step> steps(StepOperations stepOperations) {
        List<Step> steps = new ArrayList<>();
        steps.addAll(DeployFullStackAwsMain.steps(stepOperations));
        steps.add(stepOperations.checkAwsSparkConnectorTalkingToAws());
        steps.add(stepOperations.snowflakeReadsAwsViaPolaris());
        return steps;
    }

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        return steps(stepOperations);
    }

    public static void main(String[] args) {
        StepRunner app = new StepRunner();
        app.run(new DeploySnowflakeReadingAwsViaAwsMain(), args);
    }

}
