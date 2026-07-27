package health.emrys.afon.spark;

import org.apache.spark.sql.SparkSession;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.IOException;

import static org.slf4j.LoggerFactory.getLogger;

abstract public class SparkChecker {
    private static final Logger logger = getLogger(SparkChecker.class);

    public static final String EXTERNAL_NAMESPACE = "namespaceph";
    public static final String TABLE              = "test_table";

    public static void parseAndExecute(String[] args, SparkChecker app) {
        try {
            SparkSession spark     = null;
            String       warehouse = null;
            String       namespace = EXTERNAL_NAMESPACE;
            if (args.length >= 4) {
                warehouse = args[0];
                String polarisId     = args[1];
                String polarisSecret = args[2];
                String polarisHost   = args[3];
                if (args.length > 4) {
                    namespace = args[4];
                }
                spark = app.sparkSessionUsing(polarisHost, polarisId, polarisSecret, warehouse);
            }
            app.doCheck(spark, warehouse, namespace);
            logger.info("Finished without error");
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    protected static @NonNull String tableFQN(String warehouse, String namespace) {
        return warehouse + "." + namespace + "." + TABLE;
    }


    protected abstract void doCheck(SparkSession spark,
                                    String warehouse,
                                    String namespace);

    public SparkSession sparkSessionUsing(String polarisHost,
                                          String polarisId,
                                          String polarisSecret,
                                          String warehouse) throws IOException {
        SparkSession spark;
        String       protocol = "https";
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

}
