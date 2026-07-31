package health.emrys.afon.ops;

import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepRunner;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.ArrayList;
import java.util.List;

public class DeploySnowflakeReadingGcpViaAwsMain implements StepsProvider {

    public static List<Step> steps(StepOperations stepOperations) {
        List<Step> steps = new ArrayList<>();
        steps.addAll(List.of(
                stepOperations.deleteAwsStack(),
                stepOperations.deployAwsFullCluster(),
                stepOperations.waitForAwsPolaris(),
                stepOperations.initializeAllCatalogsInAws(),
                stepOperations.checkAwsSparkConnectorTalkingToAws()
        ));
        steps.add(stepOperations.checkAwsSparkConnectorReadingGcp());
        steps.add(stepOperations.snowflakeReadsGcpViaPolaris());
        return steps;
    }

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        return steps(stepOperations);
    }

    public static void main(String[] args) {
        new StepRunner().runUsing(new DeploySnowflakeReadingGcpViaAwsMain(), args);
    }

}
