package health.emrys.afon.spark;

import health.emrys.afon.config.AfonCloud;
import org.apache.spark.SparkConf;
import org.apache.spark.sql.internal.SQLConf$;
import org.apache.spark.sql.internal.StaticSQLConf$;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static health.emrys.afon.security.SecurityUtils.OAUTH_SCOPE;
import static java.lang.String.format;
import static org.apache.iceberg.CatalogUtil.ICEBERG_CATALOG_TYPE_REST;

public class SparkWarehouseConfig {

    public static final String SPARK_IMAGE = "ph1ll1phenry/spark_jars:4.0.1";

    private static final Logger logger = LoggerFactory.getLogger(SparkWarehouseConfig.class);

    private final String SPARK_SESSION_EXTENSIONS = StaticSQLConf$.MODULE$.SPARK_SESSION_EXTENSIONS().key();
    private final String DEFAULT_CATALOG          = SQLConf$.MODULE$.DEFAULT_CATALOG().key();
    private final String WAREHOUSE_PATH           = StaticSQLConf$.MODULE$.WAREHOUSE_PATH().key();

    private final String clientId;
    private final String clientSecret;
    private final String host;
    private final String polarisProtocol;

    SparkWarehouseConfig(
        String clientId,
        String clientSecret,
        String host,
        String polarisProtocol) {
        this.clientId        = clientId;
        this.clientSecret    = ***REMOVED***
        this.host            = host;
        this.polarisProtocol = polarisProtocol;
    }

    public SparkConf sparkConf() throws IOException {
        return sparkConf(4);
    }

    public SparkConf sparkConf(int cores) throws IOException {
        return sparkConf(cores, cloudProviders());
    }

    protected String[] cloudProviders() {
        return Arrays.stream(AfonCloud.values()).map(x -> x.toString()).toArray(String[]::new);
    }

    public SparkConf sparkConf(int cores, String... cloudProviders) throws IOException {
        String tmpDir = Files.createTempDirectory("SparkForTesting").toString();
        SparkConf sparkConf = new SparkConf()
            .setMaster(format("local[%d]", cores))
            .setAppName("Tests")
            .setSparkHome(tmpDir)
            .set(WAREHOUSE_PATH, tmpDir);
        configure(sparkConf, cloudProviders);

        return sparkConf;
    }

    protected void configure(SparkConf sparkConf, String[] cloudProviders) {
        for (String cloudProvider : cloudProviders) {
            logger.info("Configuring Spark context for cloud {}", cloudProvider);
            configureWith(sparkConfigs(cloudProvider), sparkConf);
        }

        configureWith(sparkConfigStatics(), sparkConf);
    }

    public static void configureWith(Map<String, String> sparkProperties, SparkConf sparkConf) {
        sparkProperties.entrySet().stream().forEach(entry -> {
            sparkConf.set(entry.getKey(), entry.getValue());
        });
    }

    public Map<String, String> sparkConfigs(AfonCloud sparkCatalog) {
        return sparkConfigs(sparkCatalog.toString());
    }

    public Map<String, String> sparkConfigs(String sparkCatalog) {
        return sparkConfigs(sparkCatalog, sparkCatalog);
    }

    public Map<String, String> sparkConfigs(String sparkCatalog, String polarisCatalog) {
        Map conf = new HashMap<String, String>();
        conf.put(format("spark.sql.catalog.%s", sparkCatalog), "org.apache.iceberg.spark.SparkCatalog");
        conf.put(format("spark.sql.catalog.%s.cache-enabled", sparkCatalog), "false");
        conf.put(format("spark.sql.catalog.%s.credential", sparkCatalog), clientId + ":" + clientSecret);
        conf.put(format("spark.sql.catalog.%s.header.X-Iceberg-Access-Delegation", sparkCatalog), "vended-credentials");
        conf.put(format("spark.sql.catalog.%s.scope", sparkCatalog), OAUTH_SCOPE);
        conf.put(format("spark.sql.catalog.%s.type", sparkCatalog), ICEBERG_CATALOG_TYPE_REST);
        conf.put(format("spark.sql.catalog.%s.uri", sparkCatalog), format("%s://%s/api/catalog", polarisProtocol,
                                                                          host));
        conf.put(format("spark.sql.catalog.%s.warehouse", sparkCatalog), polarisCatalog); // this is the Polaris
        // catalog name
        return Map.copyOf(conf);
    }

    public Map<String, String> sparkConfigStatics() {
        Map conf = new HashMap<String, String>();
        conf.put(SPARK_SESSION_EXTENSIONS, "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions");
        conf.put("spark.driver.allowMultipleContexts", "true");
        conf.put("spark.sql.iceberg.planning.preserve-data-grouping", "true");
        conf.put("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions");
        conf.put("spark.sql.sources.bucketing.enabled", "true");
        conf.put("spark.sql.sources.v2.bucketing.enabled", "true");
        return conf;
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }
}
