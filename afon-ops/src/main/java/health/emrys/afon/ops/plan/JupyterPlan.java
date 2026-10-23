package health.emrys.afon.ops.plan;

import health.emrys.afon.cdk.components.JupyterComponent;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonCloudProperty;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;

import java.util.List;


/**
 * The {@link ComponentPlan} for the {@link JupyterComponent}, decorating its deployment:
 * it holds the Jupyter domain name of the cloud, which only one running stack can serve.
 * It has nothing to wait for or configure.
 */
public class JupyterPlan implements ComponentPlan {

    @Override
    public String label() {
        return "Jupyter";
    }

    @Override
    public List<AfonProperty> domains(AfonCloud cloud, AfonPropertyProvider properties) {
        return List.of(AfonCloudProperty.JUPYTER_DOMAIN_NAME.forCloud(cloud).orElseThrow());
    }
}
