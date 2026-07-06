package health.emrys.afon.platform;

import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainer;
import health.emrys.afon.config.deployment.WorkbenchSpec;

import java.util.List;

import static health.emrys.afon.cdk.TFUtils.envVar;

/**
 * Deploys RStudio Server behind oauth2-proxy. RStudio's internal login is
 * disabled ({@code DISABLE_AUTH=true}) so oauth2-proxy is the sole gate,
 * mirroring the Jupyter and VS Code setups.
 */
public class RStudioWorkbenchDeployer extends WebWorkbenchDeployer {

    static final String IMAGE = "rocker/rstudio:4.4.2";
    static final int    PORT  = 8787;

    @Override
    public String workbenchType() {
        return WorkbenchSpec.TYPE_RSTUDIO;
    }

    @Override
    protected int port() {
        return PORT;
    }

    @Override
    protected DeploymentSpecTemplateSpecContainer container() {
        return DeploymentSpecTemplateSpecContainer
            .builder()
            .name(WorkbenchSpec.TYPE_RSTUDIO)
            .image(IMAGE)
            .env(List.of(
                // Disable RStudio's internal auth — oauth2-proxy is the sole gate.
                envVar("DISABLE_AUTH", "true"),
                // rocker images require ROOT=true for the s6 init to fix permissions.
                envVar("ROOT", "true")))
            .build();
    }
}
