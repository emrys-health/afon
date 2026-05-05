package health.emrys.afon.demo;

import java.util.ArrayList;
import java.util.List;

public class DeploySnowflakeReadingGcpViaAwsMain implements StepsProvider {

    public static List<Step> steps(StepOperations stepOperations) {
        List<Step> steps = new ArrayList<>();
        steps.addAll(DeployFullStackAwsMain.steps(stepOperations));
        steps.add(stepOperations.registerGcpTableInAws());
        steps.add(stepOperations.snowflakeReadsGcpViaPolaris());
        return steps;
    }

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        return steps(stepOperations);
    }

    public static void main(String[] args) {
        StepRunner app = new StepRunner();
        app.run(new DeploySnowflakeReadingGcpViaAwsMain(), args);
    }

}
