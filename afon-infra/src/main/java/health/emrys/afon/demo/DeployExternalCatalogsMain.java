package health.emrys.afon.demo;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * This sets the cloud environments up just as they were for the TDA demo of 23/3/26.
 * The main thing is:
 * - we have all teh catalogs in AWS plus the external one that talks to:
 * - an Azure Polaris with an Azure catalog
 * - a smoke test that ensures that Spark (running locally) can talk to the AWS catalog that is a proxy for the Azure one
 */
public class DeployExternalCatalogsMain {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(DeployExternalCatalogsMain.class);

    private static final List<Step> steps(Steps steps) {
        List<Step> stackSteps = new ArrayList<>();
        stackSteps.add(steps.prepareTofu());
        stackSteps.addAll(DeployAwsFulltStackMain.steps(steps));
        stackSteps.addAll(List.of(
            steps.cleanLocalAzure(),
            steps.deployAzurePolaris(),
            steps.waitForAzurePolaris(),
            steps.purgeForeignCatalogFromAzure(),
            steps.createAzureCatalogInAzure(),
            steps.createExternalAzureCatalogInAzure(),
            steps.checkLocalSparkTalksToAzureViaAws()
                ));
        return stackSteps;
    }

    public static void main(String[] args) {
        List<Step> steps = steps(new Steps(new StepActions()));
        StepRunner app = new StepRunner();
        if (args.length == 0) {
            app.runSteps(steps);
        } else {
            String firstStep  = args[0];
            app.runFrom(steps, firstStep);
        }
    }

}
