package health.emrys.afon.ops.steps;

import health.emrys.afon.config.AfonCloud;

import java.util.List;

public class StepsUtils {

    public static List<Step> setupFullStackStepsUsing(StepOperations stepOperations, AfonCloud cloud) {
        return List.of(
                    stepOperations.deleteStackIn(cloud),
                    stepOperations.deployFullStackIn(cloud),
                    stepOperations.waitForPolarisAndKeycloakIn(cloud),
                    // configureOidcUsersFor(cloud) must precede createAllCatalogsFor(cloud):
                    // once Polaris defers to KeyCloak for OAuth, catalog creation authenticates as
                    // "root" confidential client that only this step creates - reversed, catalog creation
                    // fails with "invalid_client" against a KeyCloak realm/client that doesn't exist.
                    // Normally only surfaces on a fresh KeyCloak database.
                    stepOperations.configureOidcUsersFor(cloud),
                    stepOperations.createAllCatalogsFor(cloud),
                    // configurePolarisUsersOnAllCatalogsFor(cloud) must follow createAllCatalogsFor:
                    // it configures users on every catalog, which must exist first.
                    stepOperations.configurePolarisUsersOnAllCatalogsFor(cloud),
                    stepOperations.runAwsSparkWritingTo(cloud)
                );
    }
}
