package health.emrys.afon.ops.plan;

import health.emrys.afon.cdk.components.JupyterComponent;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonCloudProperty;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;


/**
 * The {@link ComponentPlan} for the {@link JupyterComponent}, decorating its deployment:
 * it holds the Jupyter domain name of the cloud, which only one running stack can serve.
 * It has nothing to wait for, and its browser client to register in the KeyCloak when the stack is set up, which is not the KeyCloak
 * component's to do: a stack without Jupyter, eg the KeyCloak-only one, would overwrite the client of the stack that has it.
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

    @Override
    public List<Step> setupSteps(StepOperations ops, AfonCloud cloud) {
        return List.of(ops.configureJupyterClientFor(cloud));
    }
}
