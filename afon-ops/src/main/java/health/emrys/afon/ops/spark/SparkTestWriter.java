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
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.util.List;

import static health.emrys.afon.ops.spark.TableUtils.registerTable;
import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Write a list of values to a test table.
 * Eg, on cloud "azure" and catalog "azure":
 *
 * ./afon.sh -a exec -ec ops.spark.SparkTestWriter -ea "azure azure test_namespace test_table a,b,c" -lc INFO
 */
public class SparkTestWriter extends SparkRunner {

    private static final Logger logger = getLogger(SparkTestWriter.class);

    public SparkTestWriter(AfonPropertyProvider properties) {
        super(properties);
    }

    public void doRun(SparkSession spark, String catalogName, String namespace, String tableName, List<String> values) {
        String tableFQN = registerTable(spark, catalogName, namespace, tableName);

        logger.info("Writing to table {}", tableFQN);
        StructType schema = new StructType(new StructField[]{
                new StructField("value", DataTypes.StringType, false, Metadata.empty())
        });
        List<Row> rows = values.stream().map(v -> RowFactory.create(v)).toList();
        logger.info("Write values {}", values);
        Dataset<Row> df = spark.createDataFrame(rows, schema);
        df.write()
          .mode("overwrite")   // or "append"
          .saveAsTable(tableFQN);
    }

    public static void main(String[] args) {
        var properties = new OpsPropertyProvider();
        parseAndExecute(new SparkTestWriter(properties), properties, args);
    }

}
