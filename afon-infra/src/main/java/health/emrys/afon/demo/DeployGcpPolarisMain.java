package health.emrys.afon.demo;

import health.emrys.afon.cdk.GcpKubernetesPolaris;

import java.util.List;

public class DeployGcpPolarisMain implements StepsProvider {

    static final List<Step> steps(StepOperations ops) {
        return List.of(
            ops.cleanLocal(GcpKubernetesPolaris.ID),
            ops.deployGcpPolaris(),
            ops.waitForGcpPolaris(),
            ops.initializeAllCatalogsInGcp(),
            ops.purgeForeignCatalogFromGcp()
                      );
    }

    public static final void main(String[] args) {
        StepRunner app = new StepRunner();
        app.run(new DeployGcpPolarisMain(), args);
    }

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        return steps(stepOperations);
    }
}
