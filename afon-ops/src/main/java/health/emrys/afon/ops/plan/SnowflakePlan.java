package health.emrys.afon.ops.plan;

import health.emrys.afon.cdk.components.SnowflakeComponent;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.ops.steps.StepsProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.config.AfonCloud.*;

/**
 * The {@link ComponentPlan} for the {@link SnowflakeComponent}, decorating its deployment:
 * it holds no domain name, needs no certificate and has nothing to wait for, as Snowflake runs outside of the stack.
 * It configures nothing when the stack is set up either, as what is run on Snowflake depends on the scenario, ie the
 * catalog it reads or the cloud it writes to, see {@link #scenarioSteps}.
 */
public class SnowflakePlan implements ComponentPlan {

    @Override
    public String label() {
        return "Snowflake";
    }

    /**
     * The scenarios of Snowflake querying, through the AWS Polaris, the catalogs of the clouds, only on AWS so far:
     * <ul>
     *   <li>{@code reading-aws}: reads the AWS catalog;</li>
     *   <li>{@code reading-azure}, {@code reading-gcp}: Spark writes to the cloud, then Snowflake reads its catalog;</li>
     *   <li>{@code writing-azure}: Snowflake writes to Azure, whose table is registered in the AWS Polaris, and Spark reads it.</li>
     * </ul>
     */
    @Override
    public Map<String, StepsProvider> scenarioSteps(AfonCloud cloud) {
        if (cloud != AWS) {
            return Map.of();
        }
        Map<String, StepsProvider> scenarios = new LinkedHashMap<>();
        scenarios.put("reading-aws", ops -> List.of(
            ops.snowflakeReadingViaAwsPolaris(ops.afonCatalogs().aws())));
        scenarios.put("reading-azure", ops -> List.of(
            ops.runSparkWritingTo(AWS, AZURE),
            ops.snowflakeReadingViaAwsPolaris(ops.afonCatalogs().azure())));
        scenarios.put("reading-gcp", ops -> List.of(
            ops.runSparkWritingTo(AWS, GCP),
            ops.snowflakeReadingViaAwsPolaris(ops.afonCatalogs().gcp())));
        scenarios.put("writing-azure", ops -> List.of(
            ops.snowflakeWritingTo(AZURE),
            ops.deleteAndRecreateAwsPolarisCatalogFor(AZURE),
            ops.registerMostRecentSnowflakeTableInAws(AZURE),
            ops.runAwsSparkReadingFrom(ops.afonCatalogs().azure())));
        return scenarios;
    }
}
