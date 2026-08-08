package health.emrys.afon.ops;

import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepRunner;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.config.AfonProperties.AfonCloud.AWS;
import static health.emrys.afon.config.AfonProperties.AfonCloud.AZURE;

/**
 * Deploy external polaris catalogs:
 * - all the catalogs are in AWS plus the external one that talks to an Azure Polaris with an Azure catalog
 * - a smoke test that ensures that Spark (running locally) can talk to the AWS catalog that is a proxy for the Azure one
 */
public class DeployExternalCatalogsMain implements StepsProvider {

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        List<Step> steps = new ArrayList<>();
        steps.addAll(List.of(
                stepOperations.deleteStack(AWS),
                stepOperations.deployFullStackIn(AWS),
                stepOperations.waitForPolarisIn(AWS),
                stepOperations.initializeAllCatalogsIn(AWS),
                stepOperations.checkAwsSparkWritingTo(AWS)
        ));
        steps.addAll(List.of(
            stepOperations.deleteStack(AZURE),
            stepOperations.deployPolarisIn(AZURE),
            stepOperations.waitForPolarisIn(AZURE),
            stepOperations.purgeForeignCatalogFrom(AZURE),
            stepOperations.createForeignCatalogIn(AZURE),
            stepOperations.checkSparkWritingViaAwsPolarisToPolarisIn(AZURE)
                ));
        return steps;
    }

    public static void main(String[] args) {
        new StepRunner().runUsing(new DeployExternalCatalogsMain(), args);
    }

}
