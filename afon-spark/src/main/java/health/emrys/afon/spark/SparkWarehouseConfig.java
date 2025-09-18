package health.emrys.afon.spark;

import org.apache.spark.SparkConf;
import org.apache.spark.sql.internal.SQLConf$;
import org.apache.spark.sql.internal.StaticSQLConf$;

import static org.apache.iceberg.CatalogUtil.ICEBERG_CATALOG_TYPE_REST;

import java.io.IOException;
import java.nio.file.Files;

import static java.lang.String.format;

public class SparkWarehouseConfig {

    private final String SPARK_SESSION_EXTENSIONS = StaticSQLConf$.MODULE$.SPARK_SESSION_EXTENSIONS().key();
    private final String DEFAULT_CATALOG = SQLConf$.MODULE$.DEFAULT_CATALOG().key();
    private final String WAREHOUSE_PATH = StaticSQLConf$.MODULE$.WAREHOUSE_PATH().key();

    private final String sparkCatalog;

    public SparkWarehouseConfig() {
        this("polaris");
    }

    public SparkWarehouseConfig(String sparkCatalog) {
        this.sparkCatalog = sparkCatalog;
    }

    public String getSparkCatalog() {
        return sparkCatalog;
    }

    public SparkConf sparkConf(
            String accessToken,
            String clientId,
            String clientSecret,
            String warehouseName,
            String namespace) throws IOException {
        String tmpDir  = Files.createTempDirectory("SparkForTesting").toString();
        return new SparkConf()
                .setMaster("local[4]")
                .setAppName("Tests")
                .set(SPARK_SESSION_EXTENSIONS, "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
                .set(format("spark.sql.catalog.%s", namespace), "org.apache.iceberg.rest.RESTCatalog")
                .set(DEFAULT_CATALOG, sparkCatalog)
                .set(WAREHOUSE_PATH, tmpDir)
                .set("spark.sql.iceberg.planning.preserve-data-grouping", "true")
                .set("spark.sql.sources.bucketing.enabled", "true")
                .set("spark.sql.sources.v2.bucketing.enabled", "true")
                .set(format("spark.sql.catalog.%s.uri", sparkCatalog), "http://localhost:8181/api/catalog")
                .set(format("spark.sql.catalog.%s.token", sparkCatalog), accessToken)
                .set(format("spark.sql.catalog.%s", sparkCatalog), "org.apache.iceberg.spark.SparkCatalog")
                .set(format("spark.sql.catalog.%s.type", sparkCatalog), ICEBERG_CATALOG_TYPE_REST)
                .set(format("spark.sql.catalog.%s.warehouse", sparkCatalog), warehouseName)
                .set(format("spark.sql.catalog.%s.credential", sparkCatalog), clientId + ":" + clientSecret)
                .set(format("spark.sql.catalog.%s.oauth2.token", sparkCatalog), accessToken)
                .set(format("spark.sql.catalog.%s.rest.auth.oauth2.scope", sparkCatalog), "PRINCIPAL_ROLE:ALL")
                .set(format("spark.sql.catalog.%s.scope", sparkCatalog), "PRINCIPAL_ROLE:ALL")
                .set(format("spark.sql.catalog.%s.client_id", sparkCatalog), clientId)
                .set(format("spark.sql.catalog.%s.client_secret", sparkCatalog), clientSecret)
                .set(format("spark.sql.catalog.%s.cache-enabled", sparkCatalog), "false")
                .set(format("spark.sql.catalog.%s.header.X-Iceberg-Access-Delegation", sparkCatalog), "vended-credentials")
                .set("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
                .setSparkHome(tmpDir)
                .set("spark.driver.allowMultipleContexts", "true");
    }

}
