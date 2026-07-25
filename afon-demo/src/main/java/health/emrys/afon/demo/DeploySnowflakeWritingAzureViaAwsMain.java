package health.emrys.afon.demo;

import health.emrys.afon.steps.Step;
import health.emrys.afon.steps.StepOperations;
import health.emrys.afon.steps.StepRunner;
import health.emrys.afon.steps.StepsProvider;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AZURE;
import static health.emrys.afon.polaris.PolarisConstants.ACCEPTANCE_TEST_NAMESPACE;

public class DeploySnowflakeWritingAzureViaAwsMain implements StepsProvider {

    public static List<Step> steps(StepOperations stepOperations) {
        ArrayList<Step> steps = new ArrayList<>();
        steps.addAll(DeployFullStackAwsMain.steps(stepOperations));
        steps.addAll(List.of(
            stepOperations.snowflakeWritesToAzure(),
            stepOperations.purgeAzureFromAwsPolarisAndRecreate(),
            stepOperations.registerMostRecentSnowflakeAzureTableInAws(),
            stepOperations.awsSparkReadTestUsing(AZURE, ACCEPTANCE_TEST_NAMESPACE)
                      )
                    );
        return steps;
    }

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        return steps(stepOperations);
    }

    public static void main(String[] args) {
        StepRunner app = new StepRunner();
        app.run(new DeploySnowflakeWritingAzureViaAwsMain(), args);
    }
}
