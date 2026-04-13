package health.emrys.afon.demo;

import java.util.List;

public class DeployAwsFulltStackMain {
    static final List<Step> steps(StepOperations stepOperations) {
        return List.of(
            stepOperations.cleanLocalAws(),
            stepOperations.deployAwsPolaris(),
            stepOperations.waitForAwsPolaris(),
            stepOperations.deployAwsSpark(),
            stepOperations.initializeAllCatalogsInAws()
                      );
    }
    public static void main(String[] args) {
        List<Step> steps = steps(new StepOperations(new StepActions()));
        StepRunner app = new StepRunner();
        if (args.length == 0) {
            app.runSteps(steps);
        } else {
            String firstStep  = args[0];
            app.runFrom(steps, firstStep);
        }
    }
}
