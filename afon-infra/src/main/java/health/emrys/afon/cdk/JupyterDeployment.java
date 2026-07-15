package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;

import java.util.List;
import java.util.Map;

/**
 * A deployable Jupyter workbench, however it is wired: the legacy single-pod
 * {@link JupyterConfig} or the multi-user {@link JupyterHubConfig}. Lets
 * {@code ProvisionedCloud#newJupyterConfig()} return either without the
 * platform layer knowing which.
 */
@FunctionalInterface
public interface JupyterDeployment {

    /**
     * Declares the workbench's resources, returning the pod labels the
     * cloud's egress {@code NetworkPolicy} should select.
     */
    Map<String, String> deployJupyter(List<ITerraformDependable> dependsOn);
}
