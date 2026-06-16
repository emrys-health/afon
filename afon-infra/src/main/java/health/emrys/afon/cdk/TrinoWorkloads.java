package health.emrys.afon.cdk;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.release.Release;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.config.AfonPropertiesLoader;
import org.jetbrains.annotations.NotNull;
import software.constructs.Construct;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.USER_POLARIS_ID;
import static health.emrys.afon.config.AfonProperties.USER_POLARIS_SECRET;
import static health.emrys.afon.config.AfonProperties.AWS_POLARIS_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperties.GOOGLE_APPLICATION_CREDENTIALS;
import static health.emrys.afon.config.CatalogUtils.OAUTH_SCOPE;
import static java.lang.String.format;

/**
 * Cloud-agnostic deployment of Trino via its official Helm chart — the counterpart to
 * {@link SparkWorkloads}. Trino has no first-party operator (unlike the Spark Kubernetes
 * Operator that {@link SparkConfig} installs and {@link SparkWorkloads} drives with CRs), so the
 * chart is the maintained deployment path, and this mirrors how the project already installs
 * cert-manager / nginx / the Spark operator (all Helm {@link Release}s).
 *
 * <p>Catalogs follow {@code SparkWarehouseConfig}: one Iceberg REST catalog per
 * {@link SupportedCloudsEnum}, all pointing at the same Polaris host with {@code warehouse} set
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
    /**
     * Matches the region in the committed scripts/trino/catalog/aws.properties.
     */
    private static final String AWS_REGION    = "eu-west-2";

    private final String polarisDomainName;
    private final String gcpJson;

    public TrinoWorkloads(Properties properties) {
        this.polarisDomainName = AWS_POLARIS_DOMAIN_NAME.getValueFrom(properties);
        this.gcpJson           = GOOGLE_APPLICATION_CREDENTIALS.getValueFrom(properties);
    }

    /**
     * Installs the Trino chart against the cluster targeted by {@code helmProvider}. The release
     * id/name is kept stable ("trino") so existing stack state is preserved and the resulting
     * {@code Service} is named {@link health.emrys.afon.infra.Defaults#TRINO_SERVICE_NAME}.
     *
     * @param scope        the CDKTF stack the release is scoped to.
     * @param helmProvider the Helm provider wired to the target cluster (Azure aliases its
     *                     provider; hence this is explicit rather than relying on a default).
     * @param id           the cloud whose catalog is listed first; every cloud is still
     *                     registered as a catalog, exactly as Spark configures every cloud.
     * @param dependsOn    must include the cluster's dependables (so the API server is reachable).
     */
    public void deploy(@NotNull Construct scope,
                       @NotNull HelmProvider helmProvider,
                       @NotNull SupportedCloudsEnum id,
                       @NotNull List<? extends ITerraformDependable> dependsOn) {
        Map<String, Object> values = Map.of(
            "image", Map.of("tag", IMAGE_TAG),
            "server", Map.of("workers", WORKERS), // Integer type
            "service", Map.of("type", "ClusterIP", "port", TRINO_PORT),
            "additionalCatalogs", catalogFiles(id));

        try {
            // JSON is valid YAML, so the existing ObjectMapper produces a values document Helm
            // accepts — avoiding a YAML dependency while keeping the multi-line catalog strings.
            String valuesYaml = new ObjectMapper().writeValueAsString(values);

            Release.Builder.create(scope, "trino")
                           .provider(helmProvider)
                           .name("trino")
                           .chart("trino")
                           .repository("https://trinodb.github.io/charts")
                           .version(CHART_VERSION)
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
    private Map<String, String> catalogFiles(SupportedCloudsEnum defaultCatalog) {
        Properties properties = new AfonPropertiesLoader().getProperties();
        String     clientId   = USER_POLARIS_ID.getValueFrom(properties);
        String     secret     = USER_POLARIS_SECRET.getValueFrom(properties);

        // LinkedHashMap so the default catalog is emitted first for readability.
        Map<String, String> catalogs = new LinkedHashMap<>();
        catalogs.put(defaultCatalog.toString(), catalogProperties(defaultCatalog, clientId, secret));
        for (SupportedCloudsEnum cloud : SupportedCloudsEnum.values()) {
            if (cloud == defaultCatalog) {
                continue;
            }
            catalogs.put(cloud.toString(), catalogProperties(cloud, clientId, secret));
        }
        return catalogs;
    }

    private String catalogProperties(SupportedCloudsEnum cloud, String clientId, String secret) {
        return format(
            """
            connector.name=iceberg
            iceberg.catalog.type=rest
            iceberg.rest-catalog.uri=https://%s/api/catalog/
            iceberg.rest-catalog.warehouse=%s
            iceberg.rest-catalog.vended-credentials-enabled=true
            iceberg.rest-catalog.security=OAUTH2
            iceberg.rest-catalog.oauth2.credential=%s:%s
            iceberg.rest-catalog.oauth2.scope=%s
            %s""",
            polarisDomainName, cloud, clientId, secret, OAUTH_SCOPE, fileSystemProperties(cloud));
    }

    /**
     * The object-store plugin Trino uses to read a warehouse's data files; vended credentials
     * from Polaris supply the actual access. Selected from the cloud-name prefix.
     */
    private String fileSystemProperties(SupportedCloudsEnum cloud) {
        String name = cloud.toString();
        if (name.startsWith("aws")) {
            return format("fs.s3.enabled=true%ns3.region=%s%n", AWS_REGION);
        }
        if (name.startsWith("azure")) {
            return "fs.azure.enabled=true\n";
        }
        if (name.startsWith("gcp")) {
            return "fs.gcs.enabled=true\ngcs.auth-type=APPLICATION_DEFAULT\n";
        }
        return "";
    }
}