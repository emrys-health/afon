package health.emrys.afon.infra.checks;

import health.emrys.afon.polaris.comms.AfonPolarisClient;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.Metadata;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.List;

import static health.emrys.afon.infra.checks.SparkSessionFactory.localSparkClusterPolaris;
import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

public class SparkCheck {

    private static final Logger logger = getLogger(SparkCheck.class);

    public static void main(String[] args) {
        try {
            SparkCheck   app            = new SparkCheck();
            SparkSession spark          = null;
            String       warehouse      = null;
            String       namespace      = "namespaceph";
            if (args.length == 1) {
                warehouse                   = args[0];
                String[]     warehouseNames = {warehouse};
                spark = localSparkClusterPolaris(warehouseNames);
            } else {
                warehouse                       = args[0];
                String            polarisId     = args[1];
                String            polarisSecret = args[2];
                String            polarisHost   = args[3];
                if (args.length > 4) {
                    namespace = args[4];
                }
                String            protocol      = "https";
                AfonPolarisClient polaris       = new AfonPolarisClient(protocol + "://" + polarisHost, polarisId, polarisSecret);
                SparkSessionFactory factory = new SparkSessionFactory(
                    polaris.getAccessToken(),
                    polarisId,
                    polarisSecret,
                    polarisHost,
                    protocol,
                    new String[]{warehouse}
                );
                spark = factory.createSparkSession();
            }
            app.talkTo(spark, warehouse, namespace);
            logger.info("Finished without error");
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    public void talkTo(SparkSession spark, String warehouse, String namespace) {
        String namespaceSql = format("CREATE NAMESPACE IF NOT EXISTS %s.%s", warehouse, namespace);
        logger.info("Executing: {}", namespaceSql);
        spark.sql(namespaceSql);
        List<Row> rows = Arrays.asList(
            RowFactory.create("apple"),
            RowFactory.create("banana"),
            RowFactory.create("cherry")
                                      );

        StructType schema = new StructType(new StructField[]{
            new StructField("value", DataTypes.StringType, false, Metadata.empty())
        });
        String table     = "test_table";
        String fqn       = warehouse + "." + namespace + "." + table;
        logger.info("Writing to: {}", fqn);
        Dataset<Row> df = spark.createDataFrame(rows, schema);

        String createTableSql = format("CREATE TABLE IF NOT EXISTS %s (value String) USING iceberg ", fqn);
        logger.info("Executing: {}", createTableSql);
        spark.sql(createTableSql);

        df.write()
          .mode("overwrite")   // or "append"
          .saveAsTable(fqn);
    }

}
