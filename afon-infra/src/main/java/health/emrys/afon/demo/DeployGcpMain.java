package health.emrys.afon.demo;

import java.util.List;

public class DeployGcpMain implements StepsProvider {

    static final List<Step> steps(StepOperations actions) {
        return List.of(
            actions.cleanLocalGcp(),
            actions.deployGcpPolaris()
                      );
    }

    public static final void main(String[] args) {
        StepRunner app = new StepRunner();
        app.run(new DeployGcpMain(), args);
    }

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        return steps(stepOperations);
    }
}
