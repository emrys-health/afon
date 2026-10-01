package health.emrys.afon.ops.steps;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.StackId;

import java.util.ArrayList;
import java.util.List;

public class StepsUtils {

    /**
     * The steps setting up the full stack in the cloud, first deleting every stack in the cloud.
     */
    public static List<Step> setupFullStackStepsUsing(StepOperations stepOperations, AfonCloud cloud) {
        return setupFullStackStepsUsing(stepOperations, cloud, List.of(stepOperations.deleteStackIn(cloud)));
    }

    /**
     * The steps setting up the full stack in the cloud, after the given steps, eg deleting the stacks it conflicts with.
     */
    public static List<Step> setupFullStackStepsUsing(StepOperations stepOperations, AfonCloud cloud, List<Step> beforeDeploy) {
        StackId fullStack = new StackId(cloud, "full");
        List<Step> steps = new ArrayList<>(beforeDeploy);
        steps.addAll(List.of(
                    stepOperations.deployFullStackIn(cloud),
                    stepOperations.waitForPolarisAndKeycloakIn(cloud),
                    stepOperations.deployComplete(fullStack),
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
                    stepOperations.setupComplete(fullStack)
                ));
        return steps;
    }
}
