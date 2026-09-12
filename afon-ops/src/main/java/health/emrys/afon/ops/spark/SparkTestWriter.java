package health.emrys.afon.ops.spark;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.config.OpsPropertyProvider;
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

import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

public class SparkTestWriter extends SparkRunner {

    private static final Logger logger = getLogger(SparkTestWriter.class);

    public SparkTestWriter(AfonPropertyProvider properties) {
        super(properties);
    }


    public void doRun(SparkSession spark, String catalogName, String namespace, String tableName) {
        String namespaceSql = format("CREATE NAMESPACE IF NOT EXISTS %s", namespaceFQN(catalogName, namespace));
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
        String tableFQN = tableFQN(catalogName, namespace, tableName);
        logger.info("Writing to: {}", tableFQN);
        Dataset<Row> df = spark.createDataFrame(rows, schema);

        String createTableSql = format("CREATE TABLE IF NOT EXISTS %s (value String) USING iceberg ", tableFQN);
        logger.info("Executing: {}", createTableSql);
        spark.sql(createTableSql);

        df.write()
          .mode("overwrite")   // or "append"
          .saveAsTable(tableFQN);
    }

    public static void main(String[] args) {
        var properties = new OpsPropertyProvider();
        parseAndExecute(new SparkTestWriter(properties), properties, args);
    }

}
