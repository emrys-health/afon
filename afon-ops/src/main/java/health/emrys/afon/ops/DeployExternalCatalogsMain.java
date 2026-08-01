package health.emrys.afon.ops;

import health.emrys.afon.ops.steps.*;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * This sets the cloud environments up just as they were for the TDA ops of 23/3/26.
 * The main thing is:
 * - we have all the catalogs in AWS plus the external one that talks to:
 * - an Azure Polaris with an Azure catalog
 * - a smoke test that ensures that Spark (running locally) can talk to the AWS catalog that is a proxy for the Azure one
 */
public class DeployExternalCatalogsMain implements StepsProvider {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(DeployExternalCatalogsMain.class);

    public static final List<Step> steps(StepOperations stepOperations) {
        List<Step> stackSteps = new ArrayList<>();
        stackSteps.addAll(List.of(
                stepOperations.deleteAwsStack(),
                stepOperations.deployAwsFullStack(),
                stepOperations.waitForAwsPolaris(),
                stepOperations.initializeAllCatalogsInAws(),
                stepOperations.checkAwsSparkConnectorTalkingToAws()
        ));
        stackSteps.addAll(List.of(
            stepOperations.deleteAzureStack(),
            stepOperations.deployAzurePolaris(),
            stepOperations.waitForAzurePolaris(),
            stepOperations.purgeForeignCatalogFromAzure(),
            stepOperations.createAzureCatalogInAzure(),
//            stepOperations.createExternalAzureCatalogInAzure(), // not sure this is necessary and it fails because of the line above
            stepOperations.checkLocalSparkTalksToAzurePolarisViaAwsPolaris()
                ));
        return stackSteps;
    }

    public static void main(String[] args) {
        new StepRunner().runUsing(new DeployExternalCatalogsMain(), args);
    }

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        return steps(stepOperations);
    }
}
