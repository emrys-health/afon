package health.emrys.afon.demo;

import java.util.List;

import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AZURE;
import static health.emrys.afon.polaris.PolarisConstants.ACCEPTANCE_TEST_NAMESPACE;

public class DeploySnowflakeWritingAzureViaAwsMain implements StepsProvider {
    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        return List.of(
            stepOperations.registerMostRecentSnowflakeAzureTableInAws(),
            stepOperations.awsSparkReadTestUsing(AZURE, ACCEPTANCE_TEST_NAMESPACE)
            );
    }

    public static void main(String[] args) {
        StepRunner app = new StepRunner();
        app.run(new DeploySnowflakeWritingAzureViaAwsMain(), args);
    }
}
