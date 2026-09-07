package health.emrys.afon.ops;

import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepRunner;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.AfonCloud.AZURE;
import static health.emrys.afon.ops.steps.StepsUtils.setupFullStackStepsUsing;

public class DeploySnowflakeWritingAzureViaAwsMain implements StepsProvider {

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        List<Step> steps = new ArrayList<>();
        steps.addAll(setupFullStackStepsUsing(stepOperations, AWS));
        steps.addAll(List.of(
                        stepOperations.snowflakeWritesTo(AZURE),
                        stepOperations.deleteAwsPolarisCatalogAndRecreateFor(AZURE),
                        stepOperations.registerMostRecentSnowflakeTableInAws(AZURE),
                        stepOperations.checkAwsSparkReadingUsing(stepOperations.afonCatalogs().azure())
                )
        );
        return steps;
    }

    public static void main(String[] args) {
        new StepRunner().runUsing(new DeploySnowflakeWritingAzureViaAwsMain(), args);
    }
}
