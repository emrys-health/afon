package health.emrys.afon.ops;

import health.emrys.afon.ops.steps.*;

import java.util.List;

public class DeployFullStackAwsMain implements StepsProvider {

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        return List.of(
            stepOperations.cleanLocalAws(),
            stepOperations.deployFullAwsCluster(),
            stepOperations.waitForAwsPolaris(),
            stepOperations.initializeAllCatalogsInAws(),
            stepOperations.checkAwsSparkConnectorTalkingToAws()
                      );
    }

    public static void main(String[] args) {
        new StepRunner().runUsing(new DeployFullStackAwsMain(), args);
    }

}
