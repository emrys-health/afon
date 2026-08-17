package health.emrys.afon.spark.connect;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.spark.SparkWarehouseConfig;
import health.emrys.afon.spark.SparkWarehouseConfigFactory;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;

/**
 * You may need to register some data in Polaris to run this.
 * If so, try running something like:
 * mvn compile -Dexec.mainClass=health.emrys.afon.core.configuration.LocalPolarisRegisterMain -Dexec.args="AWS acceptancetests AwsAccessSpec s3://emrys-afon-bucket/acceptancetests/AwsAccessSpec251117511401/metadata/00000-685ec54b-684f-48cb-bb58-6ae6aacb92e6.metadata.json emryspolaris.click" exec:java
 */
public class KeyCloakSparkConnect {

    private static final Logger log = LoggerFactory.getLogger(KeyCloakSparkConnect.class);

    private final   SparkWarehouseConfigFactory configFactory = SparkWarehouseConfigFactory.getFactory();
    protected final Properties                  properties;

    /**
     * First run:
     * kubectl port-forward pod/spark-connector-0-driver 15002:15002
     */
    public static void main(String[] args) {
        KeyCloakSparkConnect app = null;
        if (args.length > 0) {
            app = new KeyCloakSparkConnect(args[0]);
        } else {
            app = new KeyCloakSparkConnect();
        }
        app.talkToSparkConnect();
    }

    private SparkWarehouseConfig sparkConfig;

    public KeyCloakSparkConnect(String polarisEndpoint) {
        this();
        log.info("Using Polaris Endpoint {}", polarisEndpoint);
        properties.put(POLARIS_ENDPOINT.getName(), polarisEndpoint);
        sparkConfig = configFactory.getConfig(properties);
    }

    public KeyCloakSparkConnect() {
        properties = new FilePropertyProvider().getProperties();
    }

    public void talkToSparkConnect() {
        String warehouse = "test_warehouse";
        SparkSession spark = sparkWithWarehouseCalled(
            warehouse,
            AfonCloud.AWS.toString()
        );
        String tableName = warehouse + ".acceptancetests.AwsAccessSpec"; // aws_trino.acceptancetests.AwsAccessSpec
        log.info("Reading table {}", tableName);
//        spark.read().table(tableName).show(); //  this works but...
        // ...unlike the line above, running this:
        // kubectl logs -f  spark-connector-0-driver | grep SELECT
        // shows the actual query in Spark Connect's log. Otherwise, grep "scan-report"
        spark.sql(String.format("SELECT * FROM %s WHERE randomInt > -1", tableName)).show();
    }

    private SparkSession sparkWithWarehouseCalled(
        String warehouseName,
        String polarisCatalog
    ) {
        SparkSession.Builder builder = SparkSession
            .builder()
            .classic()
            .appName("test2")
            .remote("sc://localhost:15002/;token=deadbeef");

        final StringBuffer szb = new StringBuffer();
        getWarehouseConfig(warehouseName, polarisCatalog)
            .entrySet()
            .forEach(entry -> {
                String key   = entry.getKey();
                String value = entry.getValue();
                builder.config(key, value);
                szb.append(String.format("%s=%s\n", key, value));
            });
        log.info("Warehouse Config:\n\n{}", szb);

        return builder.getOrCreate();
    }

    protected Map<String, String> getWarehouseConfig(String warehouseName,
                                                     String polarisCatalog) {
        return sparkConfig.sparkConfigs(warehouseName, polarisCatalog);
    }

    protected String getSparkUser() {
        return sparkConfig.getClientId();
    }

    protected String getSparkPassword() {
        return sparkConfig.getClientSecret();
    }

}
