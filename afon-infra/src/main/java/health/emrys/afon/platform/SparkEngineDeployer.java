package health.emrys.afon.platform;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccount;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccountMetadata;
import health.emrys.afon.cdk.SparkConfig;
import health.emrys.afon.cdk.SparkWorkloads;
import health.emrys.afon.config.deployment.CatalogSpec;
import health.emrys.afon.config.deployment.EngineSpec;
import health.emrys.afon.demo.Step;
import health.emrys.afon.demo.StepActions;
import health.emrys.afon.infra.checks.SparkTestWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.POLARIS_ID;
import static health.emrys.afon.config.AfonProperties.POLARIS_SECRET;
import static health.emrys.afon.infra.Defaults.SPARK_SERVICE_ACCOUNT_NAME;
import static health.emrys.afon.infra.checks.SparkChecker.EXTERNAL_NAMESPACE;
import static health.emrys.afon.platform.Legacy.enumFor;
import static java.lang.String.format;

/**
 * Deploys Spark: the Spark Kubernetes Operator ({@link SparkConfig}), the
 * driver service account, and the SparkCluster/SparkConnect CRs
 * ({@link SparkWorkloads}). Extracted verbatim from {@code AwsWholeStack} /
 * {@code SparkClusterDeployment} / {@code AzureWholeStack}, which now delegate
 * here — construct ids are unchanged.
 * <p>
 * The two halves are separately callable because the legacy AWS path installs
 * the operator with the Polaris stack ({@code AwsWholeStack}) but applies the
 * workloads in a later stack build ({@code SparkClusterDeployment}).
 */
public class SparkEngineDeployer implements QueryEngineDeployer {

    @Override
    public String engineType() {
        return EngineSpec.TYPE_SPARK;
    }

    @Override
    public void deploy(ProvisionedCloud target, EngineSpec spec, DeploymentContext ctx) {
        SparkConfig operator = installOperator(target);
        deployWorkloads(target, operator);
        List<ITerraformDependable> sparkReady = new ArrayList<>(target.dependables());
        sparkReady.add(operator.operatorReady);
        target.exposeSparkMaster(sparkReady);
    }

    /** Operator Helm release + leases RBAC + the driver's service account. */
    public SparkConfig installOperator(ProvisionedCloud target) {
        SparkConfig operator = new SparkConfig(target.stack(), "sparkop", target.workloadReadiness());
        ServiceAccount.Builder
            .create(target.stack(), "sparkApplicationSa")
            .metadata(ServiceAccountMetadata
                          .builder()
                          .name(SPARK_SERVICE_ACCOUNT_NAME)
                          .namespace("default")
                          .build())
            .build();
        return operator;
    }

    /** SparkCluster + SparkConnect CRs pointing at the target's Polaris. */
    public void deployWorkloads(ProvisionedCloud target, SparkConfig operator) {
        List<ITerraformDependable> dependsOn = new ArrayList<>(target.dependables());
        dependsOn.add(operator.operatorReady);
        new SparkWorkloads(target.polarisDomainName())
            .deploy(target.stack(), enumFor(target.cloudId()), dependsOn);
    }

    /**
     * The smoke test the design (§5.4: "per engine: deploy → smokeTest") and
     * the legacy mains ({@code checkAwsSparkConnectorTalkingToAws}) end with:
     * local Spark writes a table to the target cloud's internal catalog via
     * its live Polaris. Skipped when the config declares no such catalog.
     */
    @Override
    public List<Step> runtimeSteps(StepActions actions,
                                   String polarisDomain,
                                   EngineSpec spec,
                                   DeploymentContext ctx) {
        return smokeTestCatalogFor(spec, ctx)
            .map(warehouse -> List.of(smokeTestStep(actions, polarisDomain, warehouse, ctx)))
            .orElse(List.of());
    }

    /** First internal, non-test-fixture catalog declared on the engine's target cloud. */
    private static Optional<String> smokeTestCatalogFor(EngineSpec spec, DeploymentContext ctx) {
        return ctx.config().flatMap(config -> config.catalogsOrEmpty().stream()
            .filter(catalog -> spec.target().equals(catalog.cloud()))
            .filter(catalog -> CatalogSpec.TYPE_INTERNAL.equals(catalog.type()))
            .filter(catalog -> !Boolean.TRUE.equals(catalog.testFixture()))
            .filter(catalog -> catalog.bucketOverride() == null)
            .map(CatalogSpec::name)
            .findFirst());
    }

    /** Mirrors {@code StepOperations#sparkTestUsing}, including the step name. */
    private static Step smokeTestStep(StepActions actions,
                                      String polarisDomain,
                                      String warehouse,
                                      DeploymentContext ctx) {
        String host = polarisDomain.contains(".")
            ? polarisDomain.substring(0, polarisDomain.indexOf('.'))
            : polarisDomain;
        return new Step(
            format("%s_spark_talks_to_%s", host, warehouse),
            format("run Spark locally such that it talks to the Polaris at %s, writes a table to %s",
                   polarisDomain, warehouse),
            () -> actions.executeMainOf(
                SparkTestWriter.class,
                new String[]{
                    warehouse,
                    POLARIS_ID.getValueFrom(ctx.properties()),
                    POLARIS_SECRET.getValueFrom(ctx.properties()),
                    polarisDomain,
                    EXTERNAL_NAMESPACE},
                new Properties()));
    }
}
