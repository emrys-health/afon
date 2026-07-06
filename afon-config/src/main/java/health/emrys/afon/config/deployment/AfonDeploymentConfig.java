package health.emrys.afon.config.deployment;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Root of the declarative deployment configuration ({@code afon-deployment.yaml}).
 * The structure is defined — and validated against — the JSON Schema in
 * {@code afon-deployment.schema.json}, which is shared with the configuration UI.
 * <p>
 * Secrets never appear in this file: any field whose name ends in {@code Ref}
 * names a property that is resolved via the existing properties mechanism
 * (see {@code AfonPropertiesLoader}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AfonDeploymentConfig(
    String apiVersion,
    DeploymentSpec deployment,
    List<CloudSpec> clouds,
    List<CatalogSpec> catalogs,
    List<EngineSpec> queryEngines,
    WorkbenchSpec workbench,
    String secretsFile) {

    public static final String API_VERSION = "afon/v1";

    public List<CatalogSpec> catalogsOrEmpty() {
        return catalogs == null ? List.of() : catalogs;
    }

    public List<EngineSpec> queryEnginesOrEmpty() {
        return queryEngines == null ? List.of() : queryEngines;
    }
}
