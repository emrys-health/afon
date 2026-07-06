package health.emrys.afon.platform;

import health.emrys.afon.cdk.aws.AwsCloudPlugin;
import health.emrys.afon.cdk.azure.AzureCloudPlugin;
import health.emrys.afon.cdk.gcp.GcpCloudPlugin;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves the plugin/deployer for each entry of an
 * {@code afon-deployment.yaml}. A plain map for now; swap for
 * {@link java.util.ServiceLoader} if plugins ever live outside this repo.
 */
public final class PluginRegistry {

    private final Map<String, CloudProviderPlugin> clouds;
    private final Map<String, QueryEngineDeployer> engines;
    private final Map<String, WorkbenchDeployer>   workbenches;

    public PluginRegistry(List<CloudProviderPlugin> clouds,
                          List<QueryEngineDeployer> engines,
                          List<WorkbenchDeployer> workbenches) {
        this.clouds      = index(clouds, CloudProviderPlugin::providerName);
        this.engines     = index(engines, QueryEngineDeployer::engineType);
        this.workbenches = index(workbenches, WorkbenchDeployer::workbenchType);
    }

    /** Everything implemented in-repo today. */
    public static PluginRegistry standard() {
        return new PluginRegistry(
            List.of(new AwsCloudPlugin(), new AzureCloudPlugin(), new GcpCloudPlugin()),
            List.of(new SparkEngineDeployer(), new TrinoEngineDeployer(), new SnowflakeEngineDeployer()),
            List.of(new JupyterWorkbenchDeployer(),
                    new VsCodeWorkbenchDeployer(),
                    new RStudioWorkbenchDeployer()));
    }

    public CloudProviderPlugin cloudPlugin(String providerName) {
        return require(clouds, providerName, "cloud provider");
    }

    public QueryEngineDeployer engineDeployer(String engineType) {
        return require(engines, engineType, "query engine");
    }

    public WorkbenchDeployer workbenchDeployer(String workbenchType) {
        return require(workbenches, workbenchType, "workbench");
    }

    private static <T> Map<String, T> index(List<T> items, Function<T, String> key) {
        return items.stream().collect(Collectors.toUnmodifiableMap(key, Function.identity()));
    }

    private static <T> T require(Map<String, T> map, String key, String kind) {
        T found = map.get(key);
        if (found == null) {
            throw new IllegalArgumentException(
                "No " + kind + " plugin registered for '" + key + "'; available: " + map.keySet());
        }
        return found;
    }
}
