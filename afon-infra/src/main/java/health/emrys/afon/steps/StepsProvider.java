package health.emrys.afon.steps;

import java.util.List;

public interface StepsProvider {

    List<Step> stepsUsing(StepOperations stepOperations);

}
