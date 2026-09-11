package health.emrys.afon.ops.spark;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import org.apache.spark.sql.SparkSession;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.ops.spark.SparkSessionFactory.createSparkSession;
import static org.slf4j.LoggerFactory.getLogger;

abstract public class SparkChecker {
    private static final Logger logger = getLogger(SparkChecker.class);

    private AfonPropertyProvider properties;

    protected SparkChecker(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    public static void parseAndExecute(SparkChecker app, AfonPropertyProvider properties, String[] args) {
        try {
            if (args.length < 4) {
                System.out.printf("""
                    SparkChecker requires at least 4 arguments:
                        cloud catalog-name namespace table-name [polaris-id] [polaris-secret]
                    args: %s    
                    """, String.join(" ", args));
                return;
            }
            AfonCloud cloud = AfonCloud.valueOf(args[0].toUpperCase());
            String catalogName = args[1];
            String namespace = args[2];
            String tableName = args[3];
            String polarisId;
            String polarisSecret;
            if (args.length == 6) {
                polarisId = args[4];
                polarisSecret = args[5];
            } else {
                polarisId = properties.getValue(USER_POLARIS_ID);
                polarisSecret = properties.getValue(USER_POLARIS_SECRET);
            }
            // Using the user credentials for Polaris
            properties.setValue(POLARIS_ID, polarisId);
            properties.setValue(POLARIS_SECRET, polarisSecret);
            SparkSession spark = createSparkSession(cloud, properties, new String[]{catalogName});
            app.doCheck(spark, catalogName, namespace, tableName);
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    protected static @NonNull String namespaceFQN(String catalogName, String namespace) {
        return catalogName + "." + namespace;
    }

    protected static @NonNull String tableFQN(String catalogName, String namespace, String tableName) {
        return catalogName + "." + namespace + "." + tableName;
    }

    protected abstract void doCheck(SparkSession spark, String catalogName, String namespace, String tableName);


}
