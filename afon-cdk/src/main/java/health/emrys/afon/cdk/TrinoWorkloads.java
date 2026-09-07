package health.emrys.afon.cdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.release.Release;
import health.emrys.afon.config.AfonCatalog;
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
    /**
     * README sanity check expects {@code select version()} to report 481.
     */
    private static final String IMAGE_TAG     = "481";
    private static final int    WORKERS       = 2;
    private static final int    TRINO_PORT    = 8080;

    private final TrinoServerConfiguration trinoConfig;

    public TrinoWorkloads(AfonPropertyProvider properties,
                          String polarisDomainName) {
        this.trinoConfig = new TrinoServerConfiguration(properties, polarisDomainName);
    }

    /**
     * Installs the Trino chart against the cluster targeted by {@code helmProvider}. The release
     * id/name is kept stable ("trino") so existing stack state is preserved and the resulting
     * {@code Service} is named {@link Defaults#TRINO_SERVICE_NAME}.
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
            "image", Map.of("tag", IMAGE_TAG),
            "server", Map.of("workers", WORKERS), // Integer type
            "service", Map.of("type", "ClusterIP", "port", TRINO_PORT),
            "additionalCatalogs", catalogFiles(id.toString()));

        try {
            // JSON is valid YAML, so the existing ObjectMapper produces a values document Helm
            // accepts — avoiding a YAML dependency while keeping the multi-line catalog strings.
            String valuesYaml = new ObjectMapper().writeValueAsString(values);

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
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize Trino Helm values", e);
        }
    }

    /**
     * One catalog per supported cloud, keyed by the cloud name (the chart writes each entry to
     * {@code <name>.properties}). Mirrors {@code SparkWarehouseConfig.sparkConfigs}.
     */
    private Map<String, String> catalogFiles(String defaultCatalog) {
        // LinkedHashMap so the default catalog is emitted first for readability.
        Map<String, String> catalogs = new LinkedHashMap<>();
        catalogs.put(defaultCatalog, trinoConfig.catalogProperties(defaultCatalog));
        for (AfonCatalog catalog : AfonCatalog.values()) {
            String catalogName = catalog.toString();
            if (catalogName == defaultCatalog) {
                continue;
            }
            catalogs.put(catalogName, trinoConfig.catalogProperties(catalogName));
        }
        return catalogs;
    }


}