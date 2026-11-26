package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.*;
import health.emrys.afon.project.ProjectStackUserProvider;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.kube.tools.PortForwarder;
import health.emrys.afon.ops.smoke.config.SmokeConfig;
import health.emrys.afon.polaris.config.PolarisCredentials;
import health.emrys.afon.spark.connect.SparkConnectClient;
import org.apache.spark.api.java.function.MapFunction;
import org.apache.spark.sql.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static health.emrys.afon.config.AfonCatalog.catalogFor;
import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;
import static health.emrys.afon.config.Audit.CONFIGURED_USERS_KEY;
import static health.emrys.afon.ops.config.OpsDefaults.POLARIS_NAMESPACE_EXTERNAL;
import static health.emrys.afon.ops.config.OpsDefaults.POLARIS_TABLE_NAME;
import static health.emrys.afon.ops.smoke.config.SmokeConfig.KUBERNETES_NAMESPACE;
import static health.emrys.afon.ops.smoke.config.SmokeConfig.smokeTestProperties;
import static health.emrys.afon.ops.spark.SparkTestWriter.LOG_COLUMN_NAME;
import static java.lang.String.format;

/**
 * You need have some data in Polaris to run this, eg:
 * ./afon.sh -a exec -ec ops.spark.SparkTestWriter -ea "aws aws test_namespace test_table a,b,c" -lc INFO
 * or use the default that access the table that was written by spark-polaris-smoke
 * <p>
 * ./afon.sh -a exec -ec ops.smoke.SparkConnectSmokeMain -ea "aws" -lc INFO
 * <p>
 * Using {@code spark.sql("SELECT * FROM tableFQN")}
 * instead of {@code spark.read().table(tableFQN)}
 * so that we can show the actual query in Spark Connect's log:
 * kubectl logs -f  spark-connector-0-driver | grep SELECT
 * Otherwise, we'd need to grep "scan-report"
 */
public class SparkConnectSmokeMain {

    private static final Logger logger = LoggerFactory.getLogger(SparkConnectSmokeMain.class);

    public static class SillyToStringMapper implements MapFunction<Row, String>, Serializable {
        @Override
        public String call(Row value) throws Exception {
            return value.toString();
        }
    }

    private final AfonPropertyProvider properties;
    private final AfonUserProvider     users;
    private final AfonCatalog          catalog;

    public SparkConnectSmokeMain(AfonPropertyProvider properties, AfonUserProvider users) {
        AfonCloud cloud = new SmokeConfig(properties).cloud();
        // as seen by the cloud, whose Polaris and KeyCloak the smoke test calls
        this.properties = properties.forCloud(cloud);
        this.users      = users;
        String polarisEndpoint = new PolarisCredentials(cloud, properties).getPolarisEndpoint();
        this.properties.setValue(POLARIS_ENDPOINT, polarisEndpoint);
        catalog = catalogFor(cloud, new AfonCatalogs(properties));
    }

    private SparkSession readTable(SparkConnectClient client, List<String> errors) {
        String       warehouse = "test_warehouse";
        SparkSession spark     = client.sparkSession(warehouse, catalog.toString());
        String       tableFQN  = format("%s.%s.%s", warehouse, POLARIS_NAMESPACE_EXTERNAL, POLARIS_TABLE_NAME);
        logger.info("Reading table {}", tableFQN);
        Dataset<Row> dataset = spark.sql(format("SELECT * FROM %s", tableFQN));
        errors.addAll(checkJvmUDFsOff(dataset));
        // the table is not shown, as its rows hold details that the test does not need: it checks that the query returns them
        Object[] rows = (Object[]) dataset.collect();
        errors.addAll(checkQueryReturnsRows(rows, tableFQN));
        errors.addAll(checkUsersHaveBeenRecorded(rows, tableFQN));
        return spark;
    }

    private List<String> checkJvmUDFsOff(Dataset<Row> dataset) {
        List<String> errors = new ArrayList<>();
        try {
            dataset.map(new SillyToStringMapper(), Encoders.STRING()).show();
            errors.add("Was allowed to run JVM UDF!");
        } catch (Exception ex) {
            logger.info("As exected, UDF fail: {}", ex.getMessage());
        }
        return errors;
    }

    private static List<String> checkQueryReturnsRows(Object[] rows, String tableFQN) {
        List<String> errors = new ArrayList<>();
        if (rows.length == 0) {
            errors.add(format("The query of %s returned no rows", tableFQN));
        }
        logger.info("{} has {} rows", tableFQN, rows.length);
        return errors;
    }

    private static List<String> checkUsersHaveBeenRecorded(Object[] rows, String tableFQN) {
        List<String> errors = new ArrayList<>();
        boolean recorded = Arrays.stream(rows)
                                 .map(r -> (Row) r)
                                 .anyMatch(r -> r.getAs(LOG_COLUMN_NAME).toString().contains(CONFIGURED_USERS_KEY));
        if (!recorded) {
            errors.add(format("No %s in %s", CONFIGURED_USERS_KEY, tableFQN));
        }
        logger.info("{} records the configured users", tableFQN);
        return errors;
    }

    private List<String> callSparkConnectorInCluster() throws Exception {
        List<String> errors = new ArrayList<>();
        try (KubeClient client = new KubeClient(properties)) {
            try (PortForwarder session = new PortForwarder(
                client,
                KUBERNETES_NAMESPACE,
                SmokeConfig.SPARK_CONNECTOR_POD,
                15002,
                15002)) {
                errors.addAll(checkHappyPath());
                errors.addAll(checkTokenIsMandatory());
            }
        }
        return errors;
    }

    private List<String> checkTokenIsMandatory() {
        List<String> errors = new ArrayList<>();
        try {
            String             badToken         = "bogus";
            SparkConnectClient withInvalidToken = new SparkConnectClient(properties, users.getConfiguredUser(properties), badToken);
            readTable(withInvalidToken, errors);
            errors.add("Not supposed to be able to talk to connect with token " + badToken);
        } catch (Exception e) {
            logger.info("As expected, bogus token failed", e);
        }
        return errors;
    }

    private List<String> checkHappyPath() throws IOException {
        List<String> errors = new ArrayList<>();
        SparkSession spark  = readTable(new SparkConnectClient(properties, users), errors);
        logger.info("Test successful. About to close Spark session. Please wait for threads to die...");
        spark.close(); // need to close to avoid threads hanging and blocking JVM shutdown
        return errors;
    }

    public static void main(String[] args) {
        try {
            SparkConnectSmokeMain app    = new SparkConnectSmokeMain(smokeTestProperties(), ProjectStackUserProvider.forActiveConfig());
            List<String>          errors = app.callSparkConnectorInCluster();
            if (!errors.isEmpty()) {
                String explanation = String.join("\n", errors);
                logger.error(explanation);
                throw new RuntimeException(explanation);
            }
            logger.info("Test past");
        } catch (Exception e) {
            logger.error("Error talking to cluster", e);
            throw new RuntimeException(e);
        }
    }

}

