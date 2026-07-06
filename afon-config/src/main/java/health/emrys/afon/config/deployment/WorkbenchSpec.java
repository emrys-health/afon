package health.emrys.afon.config.deployment;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The user-facing workbench, deployed to the {@code target} cloud's cluster
 * behind an ingress + auth proxy on {@code domain}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkbenchSpec(
    String type,
    String target,
    String domain,
    AuthSpec auth) {

    public static final String TYPE_JUPYTER = "jupyter";
    public static final String TYPE_VSCODE  = "vscode";
    public static final String TYPE_RSTUDIO = "rstudio";
}
