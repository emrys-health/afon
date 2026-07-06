package health.emrys.afon.platform;

import health.emrys.afon.cdk.JupyterConfig;
import health.emrys.afon.cdk.NetworkConfiguration;
import health.emrys.afon.config.deployment.WorkbenchSpec;

import java.util.Map;

import static health.emrys.afon.infra.Defaults.SPARK_CONNECTOR_NAME;

/**
 * Deploys the Jupyter workbench (notebook + VNC + Guacamole behind
 * oauth2-proxy) using the target cloud's {@link JupyterConfig} wiring.
 * Extracted from {@code AwsWholeStack} / {@code AzureWholeStack}, which now
 * delegate here — construct ids are unchanged.
 * <p>
 * Phase 2: domains and OAuth values are still read from properties inside the
 * per-cloud {@link JupyterConfig}; {@link WorkbenchSpec} starts carrying them
 * in Phase 3.
 */
public class JupyterWorkbenchDeployer implements WorkbenchDeployer {

    @Override
    public String workbenchType() {
        return WorkbenchSpec.TYPE_JUPYTER;
    }

    @Override
    public void deploy(ProvisionedCloud target, WorkbenchSpec spec, DeploymentContext ctx) {
        deployUsingProperties(target);
    }

    public void deployUsingProperties(ProvisionedCloud target) {
        JupyterConfig       jupyter       = target.newJupyterConfig();
        Map<String, String> jupyterLabels = jupyter.deployJupyter(target.dependables());
        if (target.lockDownJupyterEgress()) {
            new NetworkConfiguration(target.stack())
                .networkPolicies(jupyterLabels, SPARK_CONNECTOR_NAME);
        }
    }
}
