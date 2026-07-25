package health.emrys.afon.ops;

import health.emrys.afon.cdk.gcp.GcpKubernetesPolaris;
import health.emrys.afon.steps.Step;
import health.emrys.afon.steps.StepOperations;
import health.emrys.afon.steps.StepRunner;
import health.emrys.afon.steps.StepsProvider;

import java.util.List;

public class DeployGcpPolarisMain implements StepsProvider {

    public static List<Step> steps(StepOperations ops) {
        return List.of(
            ops.cleanLocal(GcpKubernetesPolaris.ID),
            ops.deployGcpPolaris(),
            ops.waitForGcpPolaris(),
            ops.initializeAllCatalogsInGcp()
                      );
    }

    public static void main(String[] args) {
        StepRunner app = new StepRunner();
        app.run(new DeployGcpPolarisMain(), args);
    }

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        return steps(stepOperations);
    }
}
