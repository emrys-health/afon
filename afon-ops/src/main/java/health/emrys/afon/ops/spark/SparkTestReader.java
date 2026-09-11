package health.emrys.afon.ops.spark;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;

import static org.slf4j.LoggerFactory.getLogger;

public class SparkTestReader extends SparkChecker {

    private static final Logger logger = getLogger(SparkTestWriter.class);

    protected SparkTestReader(AfonPropertyProvider properties) {
        super(properties);
    }

    @Override
    protected void doCheck(SparkSession spark, String catalogName, String namespace, String tableName) {
        spark.conf().set("spark.sql.parquet.enableVectorizedReader", "false");
        spark.conf().set("spark.sql.iceberg.vectorization.enabled", "false");
        String tableFQN = tableFQN(catalogName, namespace, tableName);
        logger.info("Reading from: {}", tableFQN);
        Dataset<Row> table = spark.table(tableFQN);
        table.show();
        // these blow up with:
        // java.lang.IllegalStateException: Invalid ADLS Credentials for storage-account afonstorageaccount.dfs.core.windows.net: empty
//        spark.sql(format("SELECT * FROM %s.snapshots", fqn)).show();
//        spark.sql(format("SELECT file_path FROM %s.files", fqn)).show();
    }

    public static void main(String[] args) {
        var properties = new FilePropertyProvider();
        parseAndExecute(new SparkTestReader(properties), properties, args);
    }

}
