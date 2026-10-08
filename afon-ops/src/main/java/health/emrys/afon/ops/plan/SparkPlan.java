package health.emrys.afon.ops.plan;

import health.emrys.afon.cdk.components.SparkComponent;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;

import static health.emrys.afon.config.AfonCloud.AWS;

/**
 * The {@link ComponentPlan} for the {@link SparkComponent}, decorating its deployment:
 * when setting the stack up, the check that Spark writes through Polaris.  It holds no domain name and needs no certificate.
 */
public class SparkPlan implements ComponentPlan {

    /**
     * The write through Spark is only checked on AWS so far.
     */
    @Override
    public List<Step> setupSteps(StepOperations ops, AfonCloud cloud) {
        return cloud == AWS ? List.of(ops.runAwsSparkWritingTo(cloud)) : List.of();
    }
}
