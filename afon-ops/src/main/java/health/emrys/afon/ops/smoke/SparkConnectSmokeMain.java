package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.config.FileUserProvider;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.kube.tools.PortForwarder;
import health.emrys.afon.ops.smoke.config.SmokeConfig;
import health.emrys.afon.polaris.config.PolarisCredentials;
import health.emrys.afon.spark.connect.SparkConnectClient;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static health.emrys.afon.config.AfonCatalog.catalogFor;
import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;
import static health.emrys.afon.ops.config.OpsDefaults.POLARIS_NAMESPACE_EXTERNAL;
import static health.emrys.afon.ops.config.OpsDefaults.POLARIS_TABLE_NAME;
import static health.emrys.afon.ops.smoke.config.SmokeConfig.KUBERNETES_NAMESPACE;
import static health.emrys.afon.ops.smoke.config.SmokeConfig.smokeTestProperties;

/**
 * You need have some data in Polaris to run this, eg:
 * ./afon.sh -a exec -ec ops.spark.SparkTestWriter -ea "aws aws test_namespace test_table a,b,c" -lc INFO
 * or use the default that access the table that was created at Setup time
 * <p>
 * ./afon.sh -a exec -ec ops.smoke.SparkConnectSmokeMain -ea "aws" -lc INFO
 * <p>
 * Using {@code spark.sql("SELECT * FROM tableFQN").show()}
 * instead of {@code spark.read().table(tableFQN).show()}
 * so that we can show the actual query in Spark Connect's log:
 * kubectl logs -f  spark-connector-0-driver | grep SELECT
 * Otherwise, we'd need to grep "scan-report"
 */
public class SparkConnectSmokeMain {

    private static final Logger logger = LoggerFactory.getLogger(SparkConnectSmokeMain.class);

    private final AfonPropertyProvider properties;
    private final AfonUserProvider users;
    private final AfonCatalog catalog;

    public SparkConnectSmokeMain(AfonPropertyProvider properties, AfonUserProvider users) {
        this.properties = properties;
        this.users = users;
        AfonCloud cloud = new SmokeConfig(properties).cloud();
        String polarisEndpoint = new PolarisCredentials(cloud, properties).getPolarisEndpoint();
        this.properties.setValue(POLARIS_ENDPOINT, polarisEndpoint);
        catalog = catalogFor(cloud, new AfonCatalogs(properties));
    }

    private SparkSession readTable(SparkConnectClient client) {
        String warehouse = "test_warehouse";
        SparkSession spark = client.sparkSession(warehouse, catalog.toString());
        String tableFQN = String.format("%s.%s.%s", warehouse, POLARIS_NAMESPACE_EXTERNAL, POLARIS_TABLE_NAME);
        logger.info("Reading table {}", tableFQN);
        spark.sql(String.format("SELECT * FROM %s", tableFQN)).show(false);
        return spark;
    }

    private void callSparkConnectorInCluster() {
        int exitCode = 0;
        try {
            KubeClient client = new KubeClient(properties);
            try (PortForwarder session = new PortForwarder(
                    client,
                    KUBERNETES_NAMESPACE,
                    SmokeConfig.SPARK_CONNECTOR_POD,
                    15002,
                    15002)) {
                SparkSession spark = readTable(new SparkConnectClient(properties, users));
                logger.info("Test successful. About to close Spark session. Please wait for threads to die...");
                spark.close(); // need to close to avoid threads hanging and blocking JVM shutdown
            }
        } catch (IOException e) {
            logger.error("Error while closing Spark Session. Please check your configuration.", e);
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    public static void main(String[] args) {
        new SparkConnectSmokeMain(smokeTestProperties(), new FileUserProvider()).callSparkConnectorInCluster();
    }

}

