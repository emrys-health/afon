package health.emrys.afon.ops.steps;

import health.emrys.afon.config.AfonCloud;

import java.util.List;

public class StepsUtils {

    /**
     * The steps setting up the full stack in the cloud, first deleting every stack in the cloud.
     */
    public static List<Step> setupFullStackStepsUsing(StepOperations stepOperations, AfonCloud cloud) {
        return setupFullStackStepsUsing(stepOperations, cloud, stepOperations.deleteStackIn(cloud));
    }

    /**
     * The steps setting up the full stack in the cloud, first deleting the stacks as given.
     */
    public static List<Step> setupFullStackStepsUsing(StepOperations stepOperations, AfonCloud cloud, Step deleteStacks) {
        return List.of(
                    deleteStacks,
                    stepOperations.deployFullStackIn(cloud),
                    stepOperations.waitForPolarisAndKeycloakIn(cloud),
                    stepOperations.deployComplete(cloud),
                    // configureOidcUsersFor(cloud) must precede createAllCatalogsFor(cloud):
                    // once Polaris defers to KeyCloak for OAuth, catalog creation authenticates as
                    // "root" confidential client that only this step creates - reversed, catalog creation
                    // fails with "invalid_client" against a KeyCloak realm/client that doesn't exist.
                    // Normally only surfaces on a fresh KeyCloak database.
                    stepOperations.configureOidcUsersFor(cloud),
                    stepOperations.createAllCatalogsFor(cloud),
                    // configurePolarisUsersOnAllCatalogsFor(cloud) must follow createAllCatalogsFor(cloud):
                    // it configures users on every catalog, which must exist first.
                    stepOperations.configurePolarisUsersOnAllCatalogsFor(cloud),
                    stepOperations.runAwsSparkWritingTo(cloud),
                    stepOperations.setupComplete(cloud)
                );
    }
}
