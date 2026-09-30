package health.emrys.afon.ops;

import health.emrys.afon.ops.steps.StackStepsFactory;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepRunner;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.List;

import static health.emrys.afon.config.AfonCloud.GCP;

/**
 * Equivalent to {@code manage-stack delete gcp-all}, deleting every stack in GCP, whose steps are defined by
 * {@link StackStepsFactory}.
 */
public class DeleteGcpStackMain implements StepsProvider {

    @Override
    public List<Step> stepsUsing(StepOperations stepOperations) {
        return new StackStepsFactory().deleteIn(GCP).stepsUsing(stepOperations);
    }

    public static void main(String[] args) {
        new StepRunner().runUsing(new DeleteGcpStackMain(), args);
    }

}
