package health.emrys.afon.demo;

import health.emrys.afon.cdk.GcpKubernetesPolaris;

import java.util.List;

import static health.emrys.afon.cdk.GcpKubernetesPolaris.GCP_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.GCP;

public class DeployGcpPolarisMain implements StepsProvider {

    static final List<Step> steps(StepOperations ops) {
//        List.of(
//            ops.cleanLocal(GcpKubernetesPolaris.ID),
//            ops.deployGcpPolaris(),
//            ops.waitFor(GCP_DOMAIN_NAME),
//            ops.purgeForeignCatalogFrom(GCP_DOMAIN_NAME, GCP),
//            ops.createAzureCatalogIn(GCP_DOMAIN_NAME),
//            ops.createExternalAzureCatalogIn(GCP_DOMAIN_NAME),
//            ops.checkLocalSparkTalksToAzureViaAws()
//               );

        return List.of(
            ops.cleanLocal(GcpKubernetesPolaris.ID),
            ops.deployGcpPolaris(),
            ops.waitFor(GCP_DOMAIN_NAME),
            ops.initializeAllCatalogsIn(GCP_DOMAIN_NAME),
            ops.purgeForeignCatalogFrom(GCP_DOMAIN_NAME, GCP)
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
