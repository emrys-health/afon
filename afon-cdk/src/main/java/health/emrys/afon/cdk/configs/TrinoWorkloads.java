package health.emrys.afon.cdk.configs;

import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.cdk.CdkHelper;
import health.emrys.afon.cdk.CdkUtils;
import health.emrys.afon.cdk.DockerImages;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.release.Release;
import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.trino.TrinoServerConfiguration;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cloud-agnostic deployment of Trino via its official Helm chart — the counterpart to
 * {@link SparkWorkloads}. Trino has no first-party operator (unlike the Spark Kubernetes
 * Operator that {@link SparkConfig} installs and {@link SparkWorkloads} drives with CRs), so the
 * chart is the maintained deployment path, and this mirrors how the project already installs
 * cert-manager / nginx / the Spark operator (all Helm {@link Release}s).
 *
 * <p>Catalogs follow {@code SparkWarehouseConfig}: one Iceberg REST catalog per
 * {@link AfonCloud}, all pointing at the same Polaris host with {@code warehouse} set
 * to the cloud's Polaris catalog name. They are passed through the chart's {@code
 * additionalCatalogs} value, so the chart's default {@code tpch}/{@code tpcds} catalogs remain.
 */
public class TrinoWorkloads {

    /**
     * Chart version pinned for reproducibility; {@link #IMAGE_TAG} overrides its appVersion.
     */
    private static final String CHART_VERSION = "1.42.2";
    private static final int    WORKERS       = 2;
    private static final int    TRINO_PORT    = 8080;

    private final TrinoServerConfiguration trinoConfig;
    private final AfonCatalogs afonCatalogs;

    public TrinoWorkloads(AfonPropertyProvider properties,
                          AfonUserProvider users,
                          String polarisDomainName) {
        this.trinoConfig = new TrinoServerConfiguration(properties, users, polarisDomainName);
        this.afonCatalogs = new AfonCatalogs(properties);
    }

    /**
     * Installs the Trino chart against the cluster targeted by {@code helmProvider}. The release
     * id/name is kept stable ("trino") so existing stack state is preserved and the resulting
     * {@code Service} is named {@link CdkHelper#TRINO_SERVICE_NAME}.
     *
     * @param scope        the CDKTF stack the release is scoped to.
     * @param helmProvider the Helm provider wired to the target cluster (Azure aliases its
     *                     provider; hence this is explicit rather than relying on a default).
     * @param id           the cloud whose catalog is listed first; every cloud is still
     *                     registered as a catalog, exactly as Spark configures every cloud.
     * @param dependsOn    must include the cluster's dependables (so the API server is reachable).
     */
    public void deploy(@NonNull Construct scope,
                       @NonNull HelmProvider helmProvider,
                       @NonNull AfonCatalog id,
                       @NonNull List<? extends ITerraformDependable> dependsOn) {
        Map<String, Object> values = Map.of(
            "image", Map.of("tag", DockerImages.TRINO_IMAGE_TAG),
            "server", Map.of("workers", WORKERS), // Integer type
            "coordinator", nodeSettings(),
            "worker", nodeSettings(),
            "service", Map.of("type", "ClusterIP", "port", TRINO_PORT),
            "additionalCatalogs", catalogFiles(id.toString()));

        // JSON is valid YAML, so a JSON serializer produces a values document Helm
        // accepts — avoiding a YAML dependency while keeping the multi-line catalog strings.
        // Canonical (key-sorted) so the values string, which Helm diffs as text, is stable.
        String valuesYaml = CdkUtils.toCanonicalJson(values);

        Release.Builder.create(scope, "trino")
                       .provider(helmProvider)
                       .name("trino")
                       .chart(CdkUtils.vendoredChart(scope, "trinoChart", "trino-" + CHART_VERSION + ".tgz"))
                       .namespace("default")
                       .atomic(true)
                       .timeout(600)
                       .values(List.of(valuesYaml))
                       .dependsOn(dependsOn)
                       .build();
    }

    /**
     * What the coordinator and the workers need to start on a cluster with little to spare.  Without requests the pods
     * are best-effort, so the JVM is starved of CPU by the pods that have them, and its startup is slower than the
     * chart's startup probe allows, which restarts it, every time: the release then times out.  The probe's timeout and
     * retries are those of a JVM that starts slowly, not of one that is hung.
     */
    private static Map<String, Object> nodeSettings() {
        return Map.of(
            "resources", Map.of("requests", Map.of("cpu", "500m", "memory", "1Gi")),
            "startupProbe", Map.of("initialDelaySeconds", 10, "periodSeconds", 5, "timeoutSeconds", 10,
                                   "failureThreshold", 60));
    }

    /**
     * One catalog per supported cloud, keyed by the cloud name (the chart writes each entry to
     * {@code <name>.properties}). Mirrors {@code SparkWarehouseConfig.sparkConfigs}.
     */
    private Map<String, String> catalogFiles(String defaultCatalog) {
        // LinkedHashMap so the default catalog is emitted first for readability.
        Map<String, String> catalogs = new LinkedHashMap<>();
        catalogs.put(defaultCatalog, trinoConfig.catalogProperties(defaultCatalog));
        // Only the generic types fit the catalog generated here (Trino also requires lowercase,
        // cloud-prefixed names); custom types such as glue were never exposed to Trino.
        for (AfonCatalog catalog : afonCatalogs.allIncluding("internal", "external")) {
            String catalogName = catalog.toString();
            if (catalogName.equals(defaultCatalog)) {
                continue;
            }
            catalogs.put(catalogName, trinoConfig.catalogProperties(catalogName));
        }
        return catalogs;
    }


}
