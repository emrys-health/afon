package health.emrys.afon.ops.spark;

import health.emrys.afon.config.*;
import health.emrys.afon.spark.SparkClient;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.slf4j.LoggerFactory.getLogger;

abstract public class SparkRunner {
    private static final Logger logger = getLogger(SparkRunner.class);


    private AfonPropertyProvider properties;

    protected SparkRunner(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    public static void parseAndExecute(SparkRunner app, AfonPropertyProvider properties, AfonUserProvider users, String[] args) {
        try {
            if (args.length < 4) {
                System.out.printf("""
                                      SparkRunner requires at least 4 arguments:
                                          cloud catalog-name namespace table-name [values-csv] [username] [password]
                                      values-csv: csv list of values to read and write
                                      args: %s    
                                      """, String.join(" ", args));
                return;
            }
            AfonCloud          cloud       = AfonCloud.valueOf(args[0].toUpperCase());
            String             catalogName = args[1];
            String             namespace   = args[2];
            String             tableName   = args[3];
            String             values;
            final List<String> lines       = new ArrayList<>();
            if (args.length == 5) {
                values = args[4];
                Arrays.stream(values.split(",")).forEach(lines::add);
            } else {
                Audit audit = new Audit(properties);
                lines.addAll(audit.configuration());
            }
            AfonUser user;
            if (args.length == 7) {
                user = AfonUserProvider.createUser(args[5], args[6]);
            } else {
                user = users.getConfiguredUser(properties);
            }
            SparkSession spark = new SparkClient(cloud, properties, user).sparkSession(catalogName);
            app.doRun(spark, catalogName, namespace, tableName, lines);
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    protected abstract void doRun(SparkSession spark, String catalogName, String namespace, String tableName, List<String> values);


}
