package health.emrys.afon.ops;

import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepRunner;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.AfonCloud.AZURE;

/**
 * Deploy AWS Full and Azure Polaris stacks
 */
public class DeployAwsFullStackAndAzurePolarisStackMain implements StepsProvider {

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        List<Step> steps = new ArrayList<>();
        steps.addAll(List.of(
                stepOperations.deleteStackIn(AWS),
                stepOperations.deployFullStackIn(AWS),
                stepOperations.waitForPolarisAndKeycloakIn(AWS),
                stepOperations.deployComplete(AWS)));
        steps.addAll(List.of(
                stepOperations.deleteStackIn(AZURE),
                stepOperations.deployPolarisStackIn(AZURE),
                stepOperations.waitForPolarisAndKeycloakIn(AZURE),
                stepOperations.deployComplete(AZURE)));
        return steps;
    }

    public static void main(String[] args) {
        new StepRunner().runUsing(new DeployAwsFullStackAndAzurePolarisStackMain(), args);
    }

}
