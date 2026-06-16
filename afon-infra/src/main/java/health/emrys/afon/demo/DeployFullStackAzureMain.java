package health.emrys.afon.demo;

import health.emrys.afon.cdk.azure.AzureKubernetesPolaris;

import java.util.List;

public class DeployFullStackAzureMain implements StepsProvider {

    static List<Step> steps(StepOperations ops) {
        return List.of(
            ops.cleanLocal(AzureKubernetesPolaris.ID),
            ops.deployWholeStackAzure(),
            ops.waitForAzurePolaris(),
            ops.initializeAllCatalogsInAzure(),
            ops.checkSparkConnectorTalkingToAzure()
                      );
    }

    public static void main(String[] args) {
        StepRunner app = new StepRunner();
        app.run(new DeployFullStackAzureMain(), args);
    }

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        return steps(stepOperations);
    }
}
