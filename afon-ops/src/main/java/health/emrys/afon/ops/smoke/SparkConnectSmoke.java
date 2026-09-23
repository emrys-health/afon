package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.ops.config.OpsDefaults;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.polaris.config.PolarisCredentials;
import health.emrys.afon.spark.connect.SparkConnectClient;
import health.emrys.afon.spark.connect.SparkConnectUsingOIDC;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;
import static health.emrys.afon.ops.config.OpsDefaults.POLARIS_NAMESPACE_EXTERNAL;
import static health.emrys.afon.ops.config.OpsDefaults.POLARIS_TABLE_NAME;

/**
 * You need have some data in Polaris to run this, eg:
 * ./afon.sh -a exec -ec ops.spark.SparkTestWriter -ea "aws aws test_namespace test_table a,b,c" -lc INFO
 *
 * First run:
 * kubectl port-forward pod/spark-connector-0-driver 15002:15002
 * Then run:
 * ./afon.sh -a exec -ec ops.smoke.SparkConnectSmoke -ea "aws" -lc INFO
 *
 * Using {@code spark.sql("SELECT * FROM tableFQN").show()}
 * instead of {@code spark.read().table(tableFQN).show()}
 * so that we can show the actual query in Spark Connect's log:
 * kubectl logs -f  spark-connector-0-driver | grep SELECT
 * Otherwise, we'd need to grep "scan-report"
 */
public class SparkConnectSmoke {

    private static final Logger logger = LoggerFactory.getLogger(SparkConnectSmoke.class);
    public static final String TEST_WAREHOUSE = "test_warehouse";
    public static final String TEST_NAMESPACE = "namespaceph";
    public static final String TEST_TABLE = "test_table";

    public void smokeTest(SparkConnectClient client, AfonCatalog catalog) {
        SparkSession spark = client.sparkSession(TEST_WAREHOUSE, catalog.toString());
        String tableFQN = String.format("%s.%s.%s", TEST_WAREHOUSE, POLARIS_NAMESPACE_EXTERNAL, POLARIS_TABLE_NAME);
        logger.info("Reading table {}", tableFQN);
        spark.sql(String.format("SELECT * FROM %s", tableFQN)).show();
    }

    public void smokeTestAllWarehouses(SparkConnectClient client, AfonCatalog catalog) {
        SparkSession spark = client.sparkSessionAllWarehouses("federatedCatalogName");
        String tableFQN = String.format("%s.%s.%s", catalog, POLARIS_NAMESPACE_EXTERNAL, POLARIS_TABLE_NAME);
        logger.info("Reading table {}", tableFQN);
        spark.sql(String.format("SELECT * FROM %s", tableFQN)).show();
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.printf("%s requires argument: cloud", SparkConnectSmoke.class.getSimpleName());
            return;
        }
        AfonCloud cloud = AfonCloud.valueOf(args[0].toUpperCase());
        var properties = new OpsPropertyProvider();
        var afonCatalogs = new AfonCatalogs(properties);
        var app = new SparkConnectSmoke();
        properties.setValue(POLARIS_ENDPOINT, new PolarisCredentials(cloud, properties).getPolarisEndpoint());
        try {
            AfonCatalog catalog = catalogFor(cloud, afonCatalogs);
            app.smokeTest(new SparkConnectClient(properties), catalog);
            app.smokeTest(new SparkConnectUsingOIDC(properties), catalog);
            app.smokeTestAllWarehouses(new SparkConnectClient(properties), catalog);
            app.smokeTestAllWarehouses(new SparkConnectUsingOIDC(properties), catalog);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static AfonCatalog catalogFor(AfonCloud cloud, AfonCatalogs catalogs) {
        switch (cloud) {
            case AWS:
                return catalogs.aws();
            case AZURE:
                return catalogs.azure();
            case GCP:
                return catalogs.gcp();
            default:
                throw new IllegalArgumentException("Unknown cloud: " + cloud);
        }
    }

}

