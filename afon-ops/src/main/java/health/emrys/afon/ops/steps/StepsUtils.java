package health.emrys.afon.ops.steps;

import health.emrys.afon.config.AfonCloud;

import java.util.List;

public class StepsUtils {

    public static List<Step> setupFullStackStepsUsing(StepOperations stepOperations, AfonCloud cloud) {
        return List.of(
                    stepOperations.deleteStackIn(cloud),
                    stepOperations.deployFullStackIn(cloud),
                    stepOperations.waitForPolarisAndKeycloakIn(cloud),
                    stepOperations.createAllCatalogsFor(cloud),
                    stepOperations.configureOidcUsersFor(cloud),
                    stepOperations.runAwsSparkWritingTo(cloud)
                );
    }
}
