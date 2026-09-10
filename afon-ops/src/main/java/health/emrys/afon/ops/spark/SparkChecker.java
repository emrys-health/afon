package health.emrys.afon.ops.spark;

import health.emrys.afon.config.AfonPropertyProvider;
import org.apache.spark.sql.SparkSession;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.IOException;

import static health.emrys.afon.config.AfonProperty.*;
import static org.slf4j.LoggerFactory.getLogger;

abstract public class SparkChecker {
    private static final Logger logger = getLogger(SparkChecker.class);
    /**
     * 'Magic' namespace that is used for acceptance tests and ops
     */
    public static final String ACCEPTANCE_TEST_NAMESPACE = "acceptancetests";
    public static final String EXTERNAL_NAMESPACE = "namespaceph";
    public static final String TEST_TABLE = "test_table";

    private AfonPropertyProvider properties;

    protected SparkChecker(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    public static void parseAndExecute(String[] args, SparkChecker app, AfonPropertyProvider properties) {
        try {
            SparkSession spark     = null;
            String       warehouse = null;
            String       namespace = EXTERNAL_NAMESPACE;
            if (args.length >= 4) {
                warehouse = args[0];
                String cloud = args[1];
                String polarisId     = args[2];
                String polarisSecret = args[3];
                if (args.length > 4) {
                    namespace = args[4];
                }
                spark = app.sparkSessionUsing(polarisHostFor(cloud, properties), polarisId, polarisSecret, warehouse);
            }
            app.doCheck(spark, warehouse, namespace);
            logger.info("Finished without error");
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private static String polarisHostFor(String cloud, AfonPropertyProvider properties) {
        switch (cloud) {
            case "aws":
                return properties.getValue(AWS_POLARIS_DOMAIN_NAME);
            case "azure":
                return properties.getValue(AZURE_POLARIS_DOMAIN_NAME);
            case "gcp":
                return properties.getValue(GCP_POLARIS_DOMAIN_NAME);
            default:
                throw new IllegalArgumentException("Unknown cloud: " + cloud);
        }
    }

    protected static @NonNull String tableFQN(String warehouse, String namespace) {
        return warehouse + "." + namespace + "." + TEST_TABLE;
    }


    protected abstract void doCheck(SparkSession spark,
                                    String warehouse,
                                    String namespace);

    public SparkSession sparkSessionUsing(String polarisHost,
                                          String polarisId,
                                          String polarisSecret,
                                          String warehouse) throws IOException {
        SparkSessionFactory factory = new SparkSessionFactory(properties, polarisHost, polarisId, polarisSecret, new String[]{warehouse});
        return factory.createSparkSession();
    }

}
