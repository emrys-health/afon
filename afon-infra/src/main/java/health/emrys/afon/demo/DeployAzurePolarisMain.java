package health.emrys.afon.demo;

import health.emrys.afon.cdk.AzureKubernetesPolaris;

import java.util.List;

public class DeployAzurePolarisMain implements StepsProvider {

    static List<Step> steps(StepOperations ops) {
        return List.of(
            ops.cleanLocal(AzureKubernetesPolaris.ID),
            ops.deployAzurePolaris(),
            ops.waitForAzurePolaris(),
            ops.initializeAllCatalogsInAzure()
                      );
    }

    public static void main(String[] args) {
        StepRunner app = new StepRunner();
        app.run(new DeployAzurePolarisMain(), args);
    }

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        return steps(stepOperations);
    }
}
