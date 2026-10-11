package health.emrys.afon.ops.plan;

import health.emrys.afon.cdk.components.SparkComponent;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.List;
import java.util.Map;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.AfonCloud.AZURE;

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

    /**
     * The scenario {@code external-azure}, only on AWS so far: Spark writes through the AWS Polaris to the external catalog hosted by
     * the Polaris of the {@code azure-polaris} stack, which must be set up too, hence the check that it answers.
     */
    @Override
    public Map<String, StepsProvider> scenarioSteps(AfonCloud cloud) {
        if (cloud != AWS) {
            return Map.of();
        }
        return Map.of("external-azure", ops -> List.of(
            ops.checkPolarisAnswers(AZURE),
            ops.deleteExternalPolarisTableFor(AZURE),
            ops.createExternalCatalogFor(AZURE),
            ops.runAwsSparkDeferringToAzurePolarisHostingExternalCatalog()));
    }
}
