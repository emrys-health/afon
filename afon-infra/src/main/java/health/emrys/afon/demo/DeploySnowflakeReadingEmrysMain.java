package health.emrys.afon.demo;

import java.util.List;

public class DeploySnowflakeReadingEmrysMain implements StepsProvider {
    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        // assumes the AWS stack is already up. Need to think about that...
        return List.of(stepOperations.registerGcpTableInAws());
    }

    public static void main(String[] args) {
        StepRunner app = new StepRunner();
        app.run(new DeploySnowflakeReadingEmrysMain(), args);
    }

}
