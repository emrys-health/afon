package health.emrys.afon.demo;

import java.util.List;

import static health.emrys.afon.cdk.GcpKubernetesPolaris.GCP_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.GCP;

public class DeployGcpMain implements StepsProvider {

    static final List<Step> steps(StepOperations ops) {
        return List.of(
            ops.cleanLocalGcp(),
            ops.deployGcpPolaris(),
            ops.waitForGcpPolaris(),
            ops.initializeAllCatalogsIn(GCP_DOMAIN_NAME),
            ops.purgeForeignCatalogFrom(GCP_DOMAIN_NAME, GCP)
                      );
    }

    public static final void main(String[] args) {
        StepRunner app = new StepRunner();
        app.run(new DeployGcpMain(), args);
    }

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        return steps(stepOperations);
    }
}
