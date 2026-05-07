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
        String fqn = tableFQN(warehouse, namespace);
        logger.info("Reading from: {}", fqn);
        Dataset<Row> table = spark.table(fqn);
        table.show();
    }
}
