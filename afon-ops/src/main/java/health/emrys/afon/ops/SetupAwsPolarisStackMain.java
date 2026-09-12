package health.emrys.afon.ops;

import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepRunner;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.List;

import static health.emrys.afon.config.AfonCloud.AWS;

public class SetupAwsPolarisStackMain implements StepsProvider {

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        return List.of(
            stepOperations.deleteStackIn(AWS),
            stepOperations.deployPolarisStackIn(AWS),
            stepOperations.waitForPolarisAndKeycloakIn(AWS),
            stepOperations.createAllCatalogsFor(AWS));
    }

    public static void main(String[] args) {
        new StepRunner().runUsing(new SetupAwsPolarisStackMain(), args);
    }

}
