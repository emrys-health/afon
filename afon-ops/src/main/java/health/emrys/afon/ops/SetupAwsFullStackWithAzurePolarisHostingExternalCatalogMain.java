package health.emrys.afon.ops;

import health.emrys.afon.config.StackId;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepRunner;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.AfonCloud.AZURE;
import static health.emrys.afon.ops.steps.StepsUtils.setupFullStackStepsUsing;

/**
 * Setup AWS full stack with Azure Polaris hosting external catalog:
 * - all the catalogs are in AWS plus the external one that talks to an Azure Polaris with an Azure catalog
 * - a smoke test that ensures that Spark (running locally) can talk to the AWS catalog that is a proxy for the Azure one
 */
public class SetupAwsFullStackWithAzurePolarisHostingExternalCatalogMain implements StepsProvider {

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        List<Step> steps = new ArrayList<>();
        steps.addAll(setupFullStackStepsUsing(stepOperations, AWS));
        steps.addAll(List.of(
            stepOperations.deleteStackIn(AZURE),
            stepOperations.deployPolarisStackIn(AZURE),
            stepOperations.waitForPolarisAndKeycloakIn(AZURE),
            // TODO: why not delete whole external catalog rather than the single table?
            stepOperations.deleteExternalPolarisTableFor(AZURE),
            stepOperations.createExternalCatalogFor(AZURE),
            stepOperations.runAwsSparkDeferringToAzurePolarisHostingExternalCatalog(),
            stepOperations.setupComplete(new StackId(AZURE, "polaris"))));
        return steps;
    }

    public static void main(String[] args) {
        new StepRunner().runUsing(new SetupAwsFullStackWithAzurePolarisHostingExternalCatalogMain(), args);
    }

}
