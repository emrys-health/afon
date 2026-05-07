package health.emrys.afon.infra.checks;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;

import static org.slf4j.LoggerFactory.getLogger;

public class SparkTestReader extends SparkChecker {

    private static final Logger logger = getLogger(SparkTestWriter.class);

    public static void main(String[] args) {
        parseAndExecute(args, new SparkTestReader());
    }

    @Override
    protected void doCheck(SparkSession spark, String warehouse, String namespace) {
        spark.conf().set("spark.sql.parquet.enableVectorizedReader", "false");
        spark.conf().set("spark.sql.iceberg.vectorization.enabled", "false");
        String fqn = tableFQN(warehouse, namespace);
        logger.info("Reading from: {}", fqn);
        Dataset<Row> table = spark.table(fqn);
        table.show();
    }
}
