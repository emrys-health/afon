package health.emrys.afon.ops;

import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepRunner;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.config.AfonCloud.AZURE;
import static health.emrys.afon.ops.CommonSteps.awsStepsUsing;

/**
 * Setup AWS full stack with Azure Polaris hosting external catalog:
 * - all the catalogs are in AWS plus the external one that talks to an Azure Polaris with an Azure catalog
 * - a smoke test that ensures that Spark (running locally) can talk to the AWS catalog that is a proxy for the Azure one
 */
public class SetupAwsFullStackWithAzurePolarisHostingExternalCatalogMain implements StepsProvider {

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        List<Step> steps = new ArrayList<>();
        steps.addAll(awsStepsUsing(stepOperations));
        steps.addAll(List.of(
            stepOperations.deleteStack(AZURE),
            stepOperations.deployPolarisIn(AZURE),
            stepOperations.waitForPolarisIn(AZURE),
            stepOperations.deleteExternalCatalogIn(AZURE),
            stepOperations.createExternalCatalogFor(AZURE),
            stepOperations.checkSparkWritingViaAwsPolarisToPolarisIn(AZURE)
                ));
        return steps;
    }

    public static void main(String[] args) {
        new StepRunner().runUsing(new SetupAwsFullStackWithAzurePolarisHostingExternalCatalogMain(), args);
    }

}
