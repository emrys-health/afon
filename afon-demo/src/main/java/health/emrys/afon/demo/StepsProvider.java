package health.emrys.afon.demo;

import java.util.List;

public interface StepsProvider {

    List<Step> stepsUsing(StepOperations stepOperations);

}
