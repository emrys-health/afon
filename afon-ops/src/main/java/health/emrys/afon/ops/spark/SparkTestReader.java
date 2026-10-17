package health.emrys.afon.ops.spark;

import health.emrys.afon.config.FileUserProvider;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;

import java.util.List;

import static health.emrys.afon.ops.spark.TableUtils.tableFQN;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Reads a list of values from a test table that have been previously written with {@link SparkTestWriter}.
 *
 * Eg, on cloud "azure" and catalog "azure":
 *
 * ./afon.sh -a exec -ec ops.spark.SparkTestReader -ea "azure azure test_namespace test_table [values-csv]" -lc INFO
 */
public class SparkTestReader extends SparkRunner {

    private static final Logger logger = getLogger(SparkTestWriter.class);

    protected SparkTestReader(AfonPropertyProvider properties) {
        super(properties);
    }

    @Override
    protected void doRun(SparkSession spark, String catalogName, String namespace, String tableName, List<String> values) {
        spark.conf().set("spark.sql.parquet.enableVectorizedReader", "false");
        spark.conf().set("spark.sql.iceberg.vectorization.enabled", "false");
        String tableFQN = tableFQN(catalogName, namespace, tableName);
        logger.info("Reading from table {}", tableFQN);
        Dataset<Row> table = spark.table(tableFQN);
        table.show();
        List<Row> rows = table.collectAsList();
        List<String> read = rows.stream().map(r -> r.getAs("value").toString()).toList();
        logger.info("Read values {}, expected {}", read, values);
    }

    public static void main(String[] args) {
        var properties = new OpsPropertyProvider();
        parseAndExecute(new SparkTestReader(properties), properties, new FileUserProvider(), args);
    }

}
