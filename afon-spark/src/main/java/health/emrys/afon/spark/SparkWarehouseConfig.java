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

    public static void main(String[] args) {
        System.out.println(SQLConf$.MODULE$.DEFAULT_CATALOG());
        System.out.println(StaticSQLConf$.MODULE$.SPARK_SESSION_EXTENSIONS());
        System.out.println(StaticSQLConf$.MODULE$.WAREHOUSE_PATH());
    }


    public SparkConf sparkConf(
            String accessToken,
            String clientId,
            String clientSecret,
            String warehouseName,
            String namespace) throws IOException {
        String catalog = "polaris";
        String tmpDir = Files.createTempDirectory("SparkForTesting").toString();
        return new SparkConf()
                .setMaster("local[4]")
                .setAppName("Tests")
                .set(SPARK_SESSION_EXTENSIONS, "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
                .set(format("spark.sql.catalog.%s", namespace), "org.apache.iceberg.rest.RESTCatalog")
                .set(DEFAULT_CATALOG, catalog)
                .set(WAREHOUSE_PATH, tmpDir)
                .set("spark.sql.iceberg.planning.preserve-data-grouping", "true")
                .set("spark.sql.sources.bucketing.enabled", "true")
                .set("spark.sql.sources.v2.bucketing.enabled", "true")
                .set(format("spark.sql.catalog.%s.uri", catalog), "http://localhost:8181/api/catalog")
                .set(format("spark.sql.catalog.%s.token", catalog), accessToken)
                .set(format("spark.sql.catalog.%s", catalog), "org.apache.iceberg.spark.SparkCatalog")
                .set(format("spark.sql.catalog.%s.type", catalog), ICEBERG_CATALOG_TYPE_REST)
                .set(format("spark.sql.catalog.%s.warehouse", catalog), warehouseName)
                .set(format("spark.sql.catalog.%s.credential", catalog), clientId + ":" + clientSecret)
                .set(format("spark.sql.catalog.%s.oauth2.token", catalog), accessToken)
                .set(format("spark.sql.catalog.%s.rest.auth.oauth2.scope", catalog), "PRINCIPAL_ROLE:ALL")
                .set(format("spark.sql.catalog.%s.scope", catalog), "PRINCIPAL_ROLE:ALL")
                .set(format("spark.sql.catalog.%s.client_id", catalog), clientId)
                .set(format("spark.sql.catalog.%s.client_secret", catalog), clientSecret)
                .set(format("spark.sql.catalog.%s.cache-enabled", catalog), "false")
                .set(format("spark.sql.catalog.%s.header.X-Iceberg-Access-Delegation", catalog), "vended-credentials")
                .set("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
                .setSparkHome(tmpDir);
    }

}
