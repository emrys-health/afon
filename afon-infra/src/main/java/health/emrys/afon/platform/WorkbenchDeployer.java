package health.emrys.afon.platform;

import health.emrys.afon.config.deployment.WorkbenchSpec;

/**
 * Deploys the (at most one) {@code workbench:} entry onto a
 * {@link ProvisionedCloud}: the user-facing environment (Jupyter today;
 * VS Code / RStudio in Phase 3) behind its auth proxy.
 */
public interface WorkbenchDeployer {

    /** "jupyter", "vscode" or "rstudio" — matched against {@link WorkbenchSpec#type()}. */
    String workbenchType();

    void deploy(ProvisionedCloud target, WorkbenchSpec spec, DeploymentContext ctx);
}
