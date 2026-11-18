package health.emrys.afon.spark;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCatalogs;
import org.apache.spark.SparkConf;
import org.apache.spark.sql.internal.StaticSQLConf$;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static health.emrys.afon.security.SecurityUtils.OAUTH_SCOPE;
import static java.lang.String.format;
import static org.apache.iceberg.CatalogUtil.ICEBERG_CATALOG_TYPE_REST;

public class SparkWarehouseConfig {

    private static final Logger logger = LoggerFactory.getLogger(SparkWarehouseConfig.class);

    private final String SPARK_SESSION_EXTENSIONS = StaticSQLConf$.MODULE$.SPARK_SESSION_EXTENSIONS().key();
    private final String WAREHOUSE_PATH           = StaticSQLConf$.MODULE$.WAREHOUSE_PATH().key();

    private final String host;
    private final String protocol;
    private final String clientId;
    private final String clientSecret;
    private final AfonCatalogs afonCatalogs;

    SparkWarehouseConfig(String host, String protocol, String clientId, String clientSecret, AfonCatalogs afonCatalogs) {
        this.host = host;
        this.protocol = protocol;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.afonCatalogs = afonCatalogs;
    }

    public SparkConf sparkConf() throws IOException {
        return sparkConf(4);
    }

    public SparkConf sparkConf(int cores) throws IOException {
        return sparkConf(cores, catalogNames());
    }

    protected String[] catalogNames() {
        return afonCatalogs.all().stream().map(AfonCatalog::toString).toArray(String[]::new);
    }

    public SparkConf sparkConf(int cores, String... catalogNames) throws IOException {
        String tmpDir = Files.createTempDirectory("SparkForTesting").toString();
        SparkConf sparkConf = new SparkConf()
            .setMaster(format("local[%d]", cores))
            .setAppName(this.getClass().getSimpleName())
            .setSparkHome(tmpDir)
            .set(WAREHOUSE_PATH, tmpDir);
        configure(sparkConf, catalogNames);

        return sparkConf;
    }

    protected void configure(SparkConf sparkConf, String[] catalogNames) {
        for (String catalogName : catalogNames) {
            logger.info("Configuring Spark context for catalog {}", catalogName);
            configureWith(sparkConf, sparkConfigs(catalogName));
        }

        configureWith(sparkConf, sparkConfigStatics());
    }

    /**
     * The settings whose values are credentials, eg {@code spark.sql.catalog.gcp.credential}, which is the user and password of the
     * catalog, or a storage account key: not to be logged.
     */
    private static final Pattern SECRET_SETTING = Pattern.compile("(?i).*(credential|password|secret|token|account\\.key|access\\.key).*");

    public static final String REDACTED = "<redacted>";

    /**
     * @return the settings as they are logged: one to a line, with the values of the credentials redacted
     */
    static String loggable(Map<String, String> config) {
        StringBuilder sb = new StringBuilder();
        config.forEach((key, value) -> sb.append(format("\n%-80s %50s", key, SECRET_SETTING.matcher(key).matches() ? REDACTED : value)));
        return sb.toString();
    }

    public static void configureWith(SparkConf sparkConf, Map<String, String> config) {
        logger.info(loggable(config));
        config.entrySet().stream().forEach(entry -> {
            sparkConf.set(entry.getKey(), entry.getValue());
        });
    }

    public Map<String, String> sparkConfigs(String sparkCatalog) {
        return sparkConfigs(sparkCatalog, sparkCatalog);
    }

    /**
     * Spark configs for a catalog authenticating with this config's client credentials. Only for
     * Spark sessions owned by that client (e.g. local Spark); never for a shared server like
     * Spark Connect, where it would grant every user the client's access.
     */
    public Map<String, String> sparkConfigs(String sparkCatalog, String catalogName) {
        Map<String, String> config = new HashMap<>(catalogConfigs(sparkCatalog, catalogName));
        config.put(format("spark.sql.catalog.%s.credential", sparkCatalog), clientId + ":" + clientSecret);
        return Map.copyOf(config);
    }

    /**
     * Spark configs for a catalog authenticating with the caller's own OIDC {@code token}, e.g. the
     * {@code OIDC_TOKEN} injected into a data scientist's pod, so Polaris authorises that user.
     */
    public Map<String, String> sparkConfigsWithToken(String sparkCatalog, String catalogName, String token) {
        Map<String, String> config = new HashMap<>(catalogConfigs(sparkCatalog, catalogName));
        config.put(format("spark.sql.catalog.%s.token", sparkCatalog), token);
        return Map.copyOf(config);
    }

    /**
     * Spark configs for a catalog of a Data Provider that a TRE project reads, authenticating with the caller's own OIDC {@code token}: its own endpoint, not the
     * stack's Polaris, and the name that the catalog has there.  There is no OAuth server of this config to refresh the token with, nor a client to
     * authenticate: the Data Provider checks the token alone, for its audience, its second factor and its project.
     *
     * @param dataProvider a catalog from {@link health.emrys.afon.config.AfonCatalogs.Source#DATA_PROVIDER}
     * @param sparkCatalog the name of the catalog in Spark, which is not that of a catalog of the stack's own, or of another Data Provider
     */
    public Map<String, String> sparkConfigsWithToken(AfonCatalog dataProvider, String sparkCatalog, String token) {
        if (dataProvider.source() != AfonCatalogs.Source.DATA_PROVIDER) {
            throw new IllegalArgumentException("Catalog '" + dataProvider.id() + "' is not from a Data Provider, but from the " + dataProvider.source());
        }
        Map<String, String> config = new HashMap<>();
        config.put(format("spark.sql.catalog.%s", sparkCatalog), "org.apache.iceberg.spark.SparkCatalog");
        config.put(format("spark.sql.catalog.%s.cache-enabled", sparkCatalog), "false");
        config.put(format("spark.sql.catalog.%s.header.X-Iceberg-Access-Delegation", sparkCatalog), "vended-credentials");
        config.put(format("spark.sql.catalog.%s.type", sparkCatalog), ICEBERG_CATALOG_TYPE_REST);
        config.put(format("spark.sql.catalog.%s.uri", sparkCatalog), dataProvider.catalogUri());
        config.put(format("spark.sql.catalog.%s.warehouse", sparkCatalog), dataProvider.name()); // this is the Data Provider's Polaris catalog name
        config.put(format("spark.sql.catalog.%s.token", sparkCatalog), token);
        return Map.copyOf(config);
    }

    /**
     * The catalog configs common to all authentication modes.
     */
    protected Map<String, String> catalogConfigs(String sparkCatalog, String catalogName) {
        Map<String, String> config = new HashMap<>();
        config.put(format("spark.sql.catalog.%s", sparkCatalog), "org.apache.iceberg.spark.SparkCatalog");
        config.put(format("spark.sql.catalog.%s.cache-enabled", sparkCatalog), "false");
        config.put(format("spark.sql.catalog.%s.header.X-Iceberg-Access-Delegation", sparkCatalog), "vended-credentials");
        config.put(format("spark.sql.catalog.%s.scope", sparkCatalog), OAUTH_SCOPE);
        config.put(format("spark.sql.catalog.%s.type", sparkCatalog), ICEBERG_CATALOG_TYPE_REST);
        config.put(format("spark.sql.catalog.%s.uri", sparkCatalog), format("%s://%s/api/catalog", protocol, host));
        config.put(format("spark.sql.catalog.%s.warehouse", sparkCatalog), catalogName); // this is the Polaris catalog name
        return Map.copyOf(config);
    }

    public Map<String, String> sparkConfigStatics() {
        Map config = new HashMap<String, String>();
        config.put(SPARK_SESSION_EXTENSIONS, "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions");
        config.put("spark.driver.allowMultipleContexts", "true");
        config.put("spark.sql.iceberg.planning.preserve-data-grouping", "true");
        config.put("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions");
        config.put("spark.sql.sources.bucketing.enabled", "true");
        config.put("spark.sql.sources.v2.bucketing.enabled", "true");
        return config;
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }
}
