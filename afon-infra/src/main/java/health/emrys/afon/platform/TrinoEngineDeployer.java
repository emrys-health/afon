package health.emrys.afon.platform;

import health.emrys.afon.cdk.TrinoWorkloads;
import health.emrys.afon.config.deployment.EngineSpec;

import static health.emrys.afon.platform.Legacy.enumFor;

/**
 * Deploys Trino via its Helm chart ({@link TrinoWorkloads}), pointed at the
 * target cloud's Polaris. Extracted from {@code SparkClusterDeployment};
 * available on any cloud whose {@link ProvisionedCloud#helmProvider()} works.
 */
public class TrinoEngineDeployer implements QueryEngineDeployer {

    @Override
    public String engineType() {
        return EngineSpec.TYPE_TRINO;
    }

    @Override
    public void deploy(ProvisionedCloud target, EngineSpec spec, DeploymentContext ctx) {
        deployUsingProperties(target, ctx);
    }

    /**
     * Catalog/config values still come from properties on both paths: deriving
     * them from {@code catalogs:} would change the synthesised Trino Helm
     * values and break the legacy-parity gate, so it waits for Phase 4 (§5.2).
     */
    public void deployUsingProperties(ProvisionedCloud target, DeploymentContext ctx) {
        // Trino only needs the cluster reachable (not the Spark operator), so it
        // depends on the cluster dependables rather than any operator-ready signal.
        new TrinoWorkloads(ctx.properties(), target.polarisDomainName())
            .deploy(target.stack(), target.helmProvider(), enumFor(target.cloudId()), target.dependables());
    }
}
