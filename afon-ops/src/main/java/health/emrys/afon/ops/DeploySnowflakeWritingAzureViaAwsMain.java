package health.emrys.afon.ops;

import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepRunner;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AZURE;
import static health.emrys.afon.polaris.PolarisConstants.ACCEPTANCE_TEST_NAMESPACE;

public class DeploySnowflakeWritingAzureViaAwsMain implements StepsProvider {

    public static List<Step> steps(StepOperations stepOperations) {
        ArrayList<Step> steps = new ArrayList<>();
        steps.addAll(List.of(
                stepOperations.cleanLocalAws(),
                stepOperations.deployFullAwsCluster(),
                stepOperations.waitForAwsPolaris(),
                stepOperations.initializeAllCatalogsInAws(),
                stepOperations.checkAwsSparkConnectorTalkingToAws()
        ));
        steps.addAll(List.of(
            stepOperations.snowflakeWritesToAzure(),
            stepOperations.purgeAzureFromAwsPolarisAndRecreate(),
            stepOperations.registerMostRecentSnowflakeAzureTableInAws(RegisterLocalPolarisMain.class),
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
        new StepRunner().runUsing(new DeploySnowflakeWritingAzureViaAwsMain(), args);
    }
}
