package health.emrys.afon.ops;

import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepRunner;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.List;

import static health.emrys.afon.config.AfonProperties.AfonCloud.GCP;

public class DeployGcpPolarisMain implements StepsProvider {

    public static List<Step> steps(StepOperations ops) {
        return List.of(
            ops.deleteStack(GCP),
            ops.deployGcpPolaris(),
            ops.waitForGcpPolaris(),
            ops.initializeAllCatalogsInGcp()
                      );
    }

    public static void main(String[] args) {
        new StepRunner().runUsing(new DeployGcpPolarisMain(), args);
    }

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        return steps(stepOperations);
    }
}
