package health.emrys.afon.ops;

import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepRunner;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.List;

import static health.emrys.afon.config.AfonCloud.GCP;

public class DeployGcpPolarisStackMain implements StepsProvider {

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        return List.of(
            stepOperations.deleteStack(GCP),
            stepOperations.deployPolarisIn(GCP),
            stepOperations.waitForPolarisIn(GCP));
    }

    public static void main(String[] args) {
        new StepRunner().runUsing(new DeployGcpPolarisStackMain(), args);
    }

}
