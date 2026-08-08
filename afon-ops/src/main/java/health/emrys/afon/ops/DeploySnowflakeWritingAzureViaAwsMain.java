package health.emrys.afon.ops;

import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepRunner;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.config.AfonProperties.AfonCloud.AWS;
import static health.emrys.afon.config.AfonProperties.AfonCloud.AZURE;

public class DeploySnowflakeWritingAzureViaAwsMain implements StepsProvider {

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
            stepOperations.snowflakeWritesTo(AZURE),
            stepOperations.purgeFromAwsPolarisAndRecreateFor(AZURE),
            stepOperations.registerMostRecentSnowflakeTableInAws(AZURE),
            stepOperations.checkAwsSparkReadingUsing(AZURE)
                      )
                    );
        return steps;
    }
    
    public static void main(String[] args) {
        new StepRunner().runUsing(new DeploySnowflakeWritingAzureViaAwsMain(), args);
    }
}
