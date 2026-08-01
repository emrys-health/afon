package health.emrys.afon.ops;

import health.emrys.afon.ops.steps.*;

import java.util.List;

public class DeployAwsFullStackMain implements StepsProvider {

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        return List.of(
            stepOperations.deleteAwsStack(),
            stepOperations.deployAwsFullStack(),
            stepOperations.waitForAwsPolaris(),
            stepOperations.initializeAllCatalogsInAws(),
            stepOperations.checkAwsSparkConnectorTalkingToAws()
                      );
    }

    public static void main(String[] args) {
        new StepRunner().runUsing(new DeployAwsFullStackMain(), args);
    }

}
