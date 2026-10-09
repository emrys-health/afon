package health.emrys.afon.ops.plan;

import health.emrys.afon.cdk.components.JupyterComponent;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;

import java.util.List;


/**
 * The {@link ComponentPlan} for the {@link JupyterComponent}, decorating its deployment:
 * it holds the Jupyter domain name of the cloud, which only one running stack can serve,
 * and needs its ACM certificate on AWS. It has nothing to wait for or configure.
 */
public class JupyterPlan implements ComponentPlan {

    @Override
    public String label() {
        return "Jupyter";
    }

    @Override
    public List<AfonProperty> domains(AfonCloud cloud) {
        return List.of(AfonProperty.forCloud(cloud, "JUPYTER_DOMAIN_NAME").orElseThrow());
    }

    @Override
    public List<AfonProperty> certificates(AfonCloud cloud) {
        return ComponentPlan.ofCloud(cloud, "JUPYTER_CERT_ARN");
    }
}
