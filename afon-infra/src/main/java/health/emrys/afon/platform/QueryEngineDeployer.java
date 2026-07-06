package health.emrys.afon.platform;

import health.emrys.afon.config.deployment.EngineSpec;
import health.emrys.afon.demo.Step;
import health.emrys.afon.demo.StepActions;

import java.util.List;

/**
 * Deploys one {@code queryEngines:} entry onto a {@link ProvisionedCloud}
 * (Spark, Trino) or against SaaS (Snowflake, which ignores the target).
 */
public interface QueryEngineDeployer {

    /** "spark", "trino" or "snowflake" — matched against {@link EngineSpec#type()}. */
    String engineType();

    void deploy(ProvisionedCloud target, EngineSpec spec, DeploymentContext ctx);

    /**
     * Steps to run *after* the infrastructure is applied. In-cluster engines
     * (Spark, Trino) synthesise into the target's Terraform stack via
     * {@link #deploy} and need none; SaaS engines (Snowflake) do all their
     * work here, at runtime, against the given cloud's live Polaris.
     */
    default List<Step> runtimeSteps(StepActions actions,
                                    String polarisDomain,
                                    EngineSpec spec,
                                    DeploymentContext ctx) {
        return List.of();
    }
}
