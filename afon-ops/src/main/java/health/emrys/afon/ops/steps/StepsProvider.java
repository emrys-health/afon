package health.emrys.afon.ops.steps;

import java.util.List;

public interface StepsProvider {

    List<Step> stepsUsing(StepOperations stepOperations);

}
