package health.emrys.afon.demo;

import java.util.List;

public class DeployAwsFulltStackMain {
    static final List<Step> steps(Steps steps) {
        return List.of(
            steps.cleanLocalAws(),
            steps.deployAwsPolaris(),
            steps.waitForAwsPolaris(),
            steps.deployAwsSpark(),
            steps.initializeAllCatalogsInAws()
                      );
    }
    public static void main(String[] args) {
        List<Step> steps = steps(new Steps(new StepActions()));
        StepRunner app = new StepRunner();
        if (args.length == 0) {
            app.runSteps(steps);
        } else {
            String firstStep  = args[0];
            app.runFrom(steps, firstStep);
        }
    }
}
