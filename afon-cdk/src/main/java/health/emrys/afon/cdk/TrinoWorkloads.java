package health.emrys.afon.cdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.release.Release;
import health.emrys.afon.cluster.Defaults;
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

    private final String polarisDomainName;
    private final TrinoServerConfiguration trinoConfig;

    public TrinoWorkloads(AfonPropertyProvider properties,
                          String polarisDomainName) {
        trinoConfig            = new TrinoServerConfiguration(polarisDomainName, properties);
        this.polarisDomainName = polarisDomainName;
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
                       @NonNull AfonCloud id,
                       @NonNull List<? extends ITerraformDependable> dependsOn) {
        Map<String, Object> values = Map.of(
            "image", Map.of("tag", IMAGE_TAG),
            "server", Map.of("workers", WORKERS), // Integer type
            "service", Map.of("type", "ClusterIP", "port", TRINO_PORT),
            "additionalCatalogs", catalogFiles(id));

        try {
            // JSON is valid YAML, so the existing ObjectMapper produces a values document Helm
            // accepts — avoiding a YAML dependency while keeping the multi-line catalog strings.
            //
            // HDRS (Patch 23): serialise with map keys SORTED, for the reason given at length
            // in NetworkConfig (Patch 19). `values` is built from nested Map.of(...), whose
            // iteration order is randomised per JVM run, and helm_release.values IS a tracked
            // Terraform attribute - so without this the Trino release shows a change on every
            // plan and is upgraded on every apply for a document that is semantically
            // identical. Confirmed by parsing the live state's copy alongside two fresh
            // synths: equal as JSON, different as strings.
            //
            // Note this also orders `additionalCatalogs` alphabetically rather than
            // default-catalog-first. That ordering was for readability only; a plan that is
            // honest about whether anything changed is worth more.
            String valuesYaml = new ObjectMapper()
                .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .writeValueAsString(values);

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
    private Map<String, String> catalogFiles(AfonCloud defaultCatalog) {
        // LinkedHashMap so the default catalog is emitted first for readability.
        Map<String, String> catalogs = new LinkedHashMap<>();
        catalogs.put(defaultCatalog.toString(), trinoConfig.catalogProperties(defaultCatalog));
        for (AfonCloud cloud : AfonCloud.values()) {
            if (cloud == defaultCatalog) {
                continue;
            }
            catalogs.put(cloud.toString(), trinoConfig.catalogProperties(cloud));
        }
        return catalogs;
    }


}