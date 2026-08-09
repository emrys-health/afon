package health.emrys.afon.ops;

import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepRunner;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.List;

import static health.emrys.afon.config.AfonCloud.AZURE;

public class DeployAzureFullStackMain implements StepsProvider {

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        return List.of(
            stepOperations.deleteStack(AZURE),
            stepOperations.deployFullStackIn(AZURE),
            stepOperations.waitForPolarisIn(AZURE),
            stepOperations.initializeAllCatalogsIn(AZURE),
            stepOperations.checkSparkWritingToPolarisIn(AZURE)
                      );
    }

    public static void main(String[] args) {
        new StepRunner().runUsing(new DeployAzureFullStackMain(), args);
    }
}
