package health.emrys.afon.spark;

import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import org.apache.spark.SparkConf;
import org.apache.spark.sql.internal.SQLConf$;
import org.apache.spark.sql.internal.StaticSQLConf$;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static java.lang.String.format;
import static org.apache.iceberg.CatalogUtil.ICEBERG_CATALOG_TYPE_REST;

public class SparkWarehouseConfig {

    private final String SPARK_SESSION_EXTENSIONS = StaticSQLConf$.MODULE$.SPARK_SESSION_EXTENSIONS().key();
    private final String DEFAULT_CATALOG = SQLConf$.MODULE$.DEFAULT_CATALOG().key();
    private final String WAREHOUSE_PATH = StaticSQLConf$.MODULE$.WAREHOUSE_PATH().key();

    private final String accessToken;
    private final String clientId;
    private final String clientSecret;

    public SparkWarehouseConfig(
                                String accessToken,
                                String clientId,
                                String clientSecret,
                                String namespace) {
        this.accessToken   = accessToken;
        this.clientId      = clientId;
        this.clientSecret  = clientSecret;
    }

    public SparkConf sparkConf() throws IOException {
        return sparkConf(4);
    }

    public SparkConf sparkConf(int cores) throws IOException {
        String tmpDir  = Files.createTempDirectory("SparkForTesting").toString();
        SparkConf sparkConf = new SparkConf()
                .setMaster(format("local[%d]", cores))
                .setAppName("Tests")
                .setSparkHome(tmpDir)
                .set(WAREHOUSE_PATH, tmpDir);
        for (SupportedCloudsEnum cloudProvider : SupportedCloudsEnum.values()) {
            sparkConfigs(cloudProvider).entrySet().stream().forEach(entry -> {
                sparkConf.set(entry.getKey(), entry.getValue());
            });
        }
        return sparkConf;
    }

    public Map<String, String> sparkConfigs(SupportedCloudsEnum sparkCatalog) {
        Map conf = new HashMap<String, String>();
        conf.put(SPARK_SESSION_EXTENSIONS, "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions");
        conf.put("spark.sql.iceberg.planning.preserve-data-grouping", "true");
        conf.put("spark.sql.sources.bucketing.enabled", "true");
        conf.put("spark.sql.sources.v2.bucketing.enabled", "true");
        conf.put(format("spark.sql.catalog.%s.uri", sparkCatalog), "http://localhost:8181/api/catalog");
        conf.put(format("spark.sql.catalog.%s.token", sparkCatalog), accessToken);
        conf.put(format("spark.sql.catalog.%s", sparkCatalog), "org.apache.iceberg.spark.SparkCatalog");
        conf.put(format("spark.sql.catalog.%s.type", sparkCatalog), ICEBERG_CATALOG_TYPE_REST);
        conf.put(format("spark.sql.catalog.%s.warehouse", sparkCatalog), sparkCatalog.toString()); // this is the Polaris catalog name
        conf.put(format("spark.sql.catalog.%s.credential", sparkCatalog), clientId + ":" + clientSecret);
        conf.put(format("spark.sql.catalog.%s.oauth2.token", sparkCatalog), accessToken);
        conf.put(format("spark.sql.catalog.%s.rest.auth.oauth2.scope", sparkCatalog), "PRINCIPAL_ROLE:ALL");
        conf.put(format("spark.sql.catalog.%s.scope", sparkCatalog), "PRINCIPAL_ROLE:ALL");
        conf.put(format("spark.sql.catalog.%s.client_id", sparkCatalog), clientId);
        conf.put(format("spark.sql.catalog.%s.client_secret", sparkCatalog), clientSecret);
        conf.put(format("spark.sql.catalog.%s.cache-enabled", sparkCatalog), "false");
        conf.put(format("spark.sql.catalog.%s.header.X-Iceberg-Access-Delegation", sparkCatalog), "vended-credentials");
        conf.put("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions");
        conf.put("spark.driver.allowMultipleContexts", "true");
        return Map.copyOf(conf);
    }
}
