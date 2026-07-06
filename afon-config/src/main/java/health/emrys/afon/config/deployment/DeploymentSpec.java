package health.emrys.afon.config.deployment;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Global identity of the deployment.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeploymentSpec(
    String name,
    String clusterName) {
}
