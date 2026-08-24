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
 * Deploy AWS full stack with Azure Polaris
 */
public class DeployAwsFullStackWithAzurePolarisMain implements StepsProvider {

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
        new StepRunner().runUsing(new DeployAwsFullStackWithAzurePolarisMain(), args);
    }

}
