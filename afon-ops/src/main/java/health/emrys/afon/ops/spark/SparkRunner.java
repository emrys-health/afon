package health.emrys.afon.ops.spark;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.spark.SparkClient;
import org.apache.spark.sql.SparkSession;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.util.List;

import static health.emrys.afon.config.AfonProperty.*;
import static org.slf4j.LoggerFactory.getLogger;

abstract public class SparkRunner {
    private static final Logger logger = getLogger(SparkRunner.class);

    private AfonPropertyProvider properties;

    protected SparkRunner(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    public static void parseAndExecute(SparkRunner app, AfonPropertyProvider properties, String[] args) {
        try {
            if (args.length < 4) {
                System.out.printf("""
                    SparkRunner requires at least 4 arguments:
                        cloud catalog-name namespace table-name [values-csv] [polaris-id] [polaris-secret]
                    values-csv: csv list of values to read and write
                    args: %s    
                    """, String.join(" ", args));
                return;
            }
            AfonCloud cloud = AfonCloud.valueOf(args[0].toUpperCase());
            String catalogName = args[1];
            String namespace = args[2];
            String tableName = args[3];
            String values;
            if (args.length == 5) {
                values = args[4];
            } else {
                values = "apple,banana,orange";
            }
            String polarisId;
            String polarisSecret;
            if (args.length == 7) {
                polarisId = args[5];
                polarisSecret = args[6];
            } else {
                polarisId = properties.getValue(USER_POLARIS_ID);
                polarisSecret = properties.getValue(USER_POLARIS_SECRET);
            }
            // Using the user credentials for Polaris
            properties.setValue(POLARIS_ID, polarisId);
            properties.setValue(POLARIS_SECRET, polarisSecret);
            SparkSession spark = new SparkClient(cloud, properties).sparkSession(catalogName);
            app.doRun(spark, catalogName, namespace, tableName, List.of(values.split(",")));
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

    protected abstract void doRun(SparkSession spark, String catalogName, String namespace, String tableName, List<String> values);


}
