package health.emrys.afon.infra.checks;

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

public class SparkTestWriter extends SparkChecker {

    private static final Logger logger = getLogger(SparkTestWriter.class);

    public static void main(String[] args) {
        parseAndExecute(args, new SparkTestWriter());
    }

    public void doCheck(SparkSession spark, String warehouse, String namespace) {
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
        String fqn = tableFQN(warehouse, namespace);
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
