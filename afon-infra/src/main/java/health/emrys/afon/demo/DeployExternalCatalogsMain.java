package health.emrys.afon.demo;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * This sets the cloud environments up just as they were for the TDA demo of 23/3/26.
 * The main thing is:
 * - we have all the catalogs in AWS plus the external one that talks to:
 * - an Azure Polaris with an Azure catalog
 * - a smoke test that ensures that Spark (running locally) can talk to the AWS catalog that is a proxy for the Azure one
 */
public class DeployExternalCatalogsMain {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(DeployExternalCatalogsMain.class);

    public static final List<Step> steps(StepOperations stepOperations) {
        List<Step> stackSteps = new ArrayList<>();
        stackSteps.addAll(DeployFullStackAwsMain.steps(stepOperations));
        stackSteps.addAll(List.of(
            stepOperations.cleanLocalAzure(),
            stepOperations.deployAzurePolaris(),
            stepOperations.waitForAzurePolaris(),
            stepOperations.purgeForeignCatalogFromAzure(),
            stepOperations.createAzureCatalogInAzure(),
            stepOperations.createExternalAzureCatalogInAzure(),
            stepOperations.checkLocalSparkTalksToAzureViaAws()
                ));
        return stackSteps;
    }

    public static void main(String[] args) {
        List<Step> steps = steps(new StepOperations(new StepActions()));
        StepRunner app = new StepRunner();
        if (args.length == 0) {
            app.runSteps(steps);
        } else {
            String firstStep  = args[0];
            app.runFrom(steps, firstStep);
        }
    }

}
