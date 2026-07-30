package health.emrys.afon.ops;

import health.emrys.afon.cdk.azure.AzureKubernetesPolaris;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepRunner;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.List;

public class DeployAzurePolarisMain implements StepsProvider {

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        return List.of(
            stepOperations.cleanLocal(AzureKubernetesPolaris.ID),
            stepOperations.deployAzurePolaris(),
            stepOperations.waitForAzurePolaris(),
            stepOperations.initializeAllCatalogsInAzure()
                      );
    }

    public static void main(String[] args) {
        new StepRunner().runUsing(new DeployAzurePolarisMain(), args);
    }

}
