package health.emrys.afon.demo;

import java.util.List;

public class DeployFullStackAwsMain implements StepsProvider {
    static final List<Step> steps(StepOperations stepOperations) {
        return List.of(
            stepOperations.cleanLocalAws(),
            stepOperations.deployAwsSpark(),
            stepOperations.waitForAwsPolaris(),
            stepOperations.initializeAllCatalogsInAws(),
            stepOperations.checkAwsSparkConnectorReadingAws()
                      );
    }
    public static void main(String[] args) {
        StepRunner app = new StepRunner();
        List<Step> steps = steps(new StepOperations(new StepActions()));
        if (args.length == 0) {
            app.runSteps(steps);
        } else {
            String firstStep  = args[0];
            app.runFrom(steps, firstStep);
        }
    }

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        return steps(stepOperations);
    }
}
