package health.emrys.afon.ops.steps;

import health.emrys.afon.config.AfonCloud;

import java.util.List;

public class StepsUtils {

    public static List<Step> setupFullStackStepsUsing(StepOperations stepOperations, AfonCloud id) {
        return List.of(
                    stepOperations.deleteStack(id),
                    stepOperations.deployFullStackIn(id),
                    stepOperations.waitForPolarisIn(id),
                    stepOperations.createAllCatalogsFor(id),
                    stepOperations.checkAwsSparkWritingTo(id)
                );
    }
}
