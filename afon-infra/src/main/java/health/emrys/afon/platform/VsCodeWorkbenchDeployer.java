package health.emrys.afon.platform;

import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainer;
import health.emrys.afon.config.deployment.WorkbenchSpec;

import java.util.List;

/**
 * Deploys code-server (VS Code in the browser) behind oauth2-proxy. Unlike
 * Jupyter there is no Guacamole/VNC layer — code-server is web-native, so the
 * chain is simply ingress → oauth2-proxy → vscode-web → code-server pod.
 */
public class VsCodeWorkbenchDeployer extends WebWorkbenchDeployer {

    static final String IMAGE = "codercom/code-server:4.96.4";
    static final int    PORT  = 8080;

    @Override
    public String workbenchType() {
        return WorkbenchSpec.TYPE_VSCODE;
    }

    @Override
    protected int port() {
        return PORT;
    }

    @Override
    protected DeploymentSpecTemplateSpecContainer container() {
        return DeploymentSpecTemplateSpecContainer
            .builder()
            .name(WorkbenchSpec.TYPE_VSCODE)
            .image(IMAGE)
            // Disable code-server's built-in password auth so oauth2-proxy is
            // the sole gate, mirroring the Jupyter setup.
            .args(List.of(
                "--auth=none",
                "--bind-addr=0.0.0.0:" + PORT))
            .build();
    }
}
