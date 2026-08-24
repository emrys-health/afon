package health.emrys.afon.ops;

import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepRunner;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.AfonCloud.AZURE;

/**
 * Deploy external polaris catalogs:
 * - all the catalogs are in AWS plus the external one that talks to an Azure Polaris with an Azure catalog
 */
public class DeployExternalCatalogsMain implements StepsProvider {

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        List<Step> steps = new ArrayList<>();
        steps.addAll(List.of(
                stepOperations.deleteStack(AWS),
                stepOperations.deployFullStackIn(AWS),
                stepOperations.waitForPolarisIn(AWS)
        ));
        steps.addAll(List.of(
            stepOperations.deleteStack(AZURE),
            stepOperations.deployPolarisIn(AZURE),
            stepOperations.waitForPolarisIn(AZURE)
                ));
        return steps;
    }

    public static void main(String[] args) {
        new StepRunner().runUsing(new DeployExternalCatalogsMain(), args);
    }

}
