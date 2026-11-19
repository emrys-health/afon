package health.emrys.afon.ops.plan;

import health.emrys.afon.cdk.components.SparkComponent;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.List;
import java.util.Map;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.AfonCloud.AZURE;
import static health.emrys.afon.config.AfonProperty.OIDC_PROJECT_CLAIM;

/**
 * The {@link ComponentPlan} for the {@link SparkComponent}, decorating its deployment:
 * when setting the stack up, the check that Spark writes through Polaris.  It holds no domain name and needs no certificate.
 */
public class SparkPlan implements ComponentPlan {

    /**
     * The write through Spark is checked on the cloud of the stack, through its Polaris, to its own catalog.
     *
     * <p>Not for the stack of a project's TRE: its Polaris admits only the tokens of the project, with its audience, its {@code acr} and its project
     * claim, which this check, a test that writes and reads a table as an admin user, cannot present.
     */
    @Override
    public List<Step> setupSteps(StepOperations ops, AfonCloud cloud) {
        if (isProject(ops.properties())) {
            return List.of();
        }
        return List.of(ops.runSparkWritingTo(cloud, cloud));
    }

    /**
     * @return whether the config is of a project's TRE, which names the claim that its tokens carry the project in: set, and not blank, as the config template has the
     * key empty
     */
    private static boolean isProject(AfonPropertyProvider properties) {
        return properties != null && properties.hasProperty(OIDC_PROJECT_CLAIM) && !properties.getValue(OIDC_PROJECT_CLAIM, "").isBlank();
    }

    /**
     * The scenario {@code external-azure}, only on AWS so far: Spark writes through the AWS Polaris to the external catalog hosted by
     * the Polaris of the Azure stack with the catalog role, which must be set up too, hence the check that it answers.
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
