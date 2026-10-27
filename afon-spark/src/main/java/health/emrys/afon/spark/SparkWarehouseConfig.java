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

    public static void configureWith(SparkConf sparkConf, Map<String, String> config) {
        StringBuffer sb = new StringBuffer();
        config.entrySet().forEach(x -> sb.append(format("\n%-80s %50s", x.getKey(), x.getValue())));
        logger.info(sb.toString());
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
