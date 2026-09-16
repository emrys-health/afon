package health.emrys.afon.ops.spark;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;

import java.util.List;

import static health.emrys.afon.ops.spark.TableUtils.registerTable;
import static org.slf4j.LoggerFactory.getLogger;

public class SparkDemoMaker extends SparkRunner {

    private static final Logger logger = getLogger(SparkDemoMaker.class);

    protected SparkDemoMaker(AfonPropertyProvider properties) {
        super(properties);
    }

    @Override
    protected void doRun(SparkSession spark, String catalogName, String namespace, String tableName, List<String> values) {
        logger.info("Parquet directories to read {}", String.join(", ", values));
        if (values.size() != 1) {
            logger.error("Expecting one and only one dataframe. Got {}", values.size());
        } else {
            String dir = values.get(0);
            logger.info("About to read parquet {}", dir);
            String       tableFQN = registerTable(spark, catalogName, namespace, tableName);
            Dataset<Row> df       = spark.read().parquet(values.get(0));
            df.show();
            df.write()
              .mode("overwrite")   // or "append"
              .saveAsTable(tableFQN);
        }
    }

    public static void main(String[] args) {
        var properties = new OpsPropertyProvider();
        parseAndExecute(new SparkDemoMaker(properties), properties, args);
    }
}
