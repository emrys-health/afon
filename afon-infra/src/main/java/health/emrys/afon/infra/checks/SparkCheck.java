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

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

public class SparkCheck {

    public static final String EXTERNAL_NAMESPACE = "namespaceph";
    public static final String TABLE = "test_table";
    private static final Logger logger = getLogger(SparkCheck.class);

    public static void main(String[] args) {
        try {
            SparkCheck   app            = new SparkCheck();
            SparkSession spark          = null;
            String       warehouse      = null;
            String       namespace      = EXTERNAL_NAMESPACE;
            if (args.length == 4) {
                warehouse                       = args[0];
                String            polarisId     = args[1];
                String            polarisSecret = args[2];
                String            polarisHost   = args[3];
                if (args.length > 4) {
                    namespace = args[4];
                }
                spark = app.sparkSessionUsing(polarisHost, polarisId, polarisSecret, warehouse);
            }
            app.talkTo(spark, warehouse, namespace);
            logger.info("Finished without error");
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    public SparkSession sparkSessionUsing(String polarisHost,
                                          String polarisId,
                                          String polarisSecret,
                                          String warehouse) throws IOException {
        SparkSession      spark;
        String            protocol = "https";
        AfonPolarisClient polaris  = new AfonPolarisClient(protocol + "://" + polarisHost, polarisId, polarisSecret);
        SparkSessionFactory factory = new SparkSessionFactory(
            polarisId,
            polarisSecret,
            polarisHost,
            protocol,
            new String[]{warehouse}
        );
        spark = factory.createSparkSession();
        return spark;
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
        String fqn   = warehouse + "." + namespace + "." + TABLE;
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
