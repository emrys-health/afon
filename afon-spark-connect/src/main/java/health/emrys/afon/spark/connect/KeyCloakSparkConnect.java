package health.emrys.afon.spark.connect;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.spark.SparkWarehouseConfig;
import health.emrys.afon.spark.SparkWarehouseConfigFactory;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.POLARIS_ENDPOINT;

public class KeyCloakSparkConnect {

    private static final Logger log = LoggerFactory.getLogger(KeyCloakSparkConnect.class);

    private final SparkWarehouseConfigFactory configFactory = SparkWarehouseConfigFactory.getFactory();
    private final Properties                  properties;

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

    public KeyCloakSparkConnect(String polarisEndpoint) {
        this();
        properties.put(POLARIS_ENDPOINT.getPropertyName(), polarisEndpoint);
    }

    public KeyCloakSparkConnect() {
        properties = new AfonPropertiesLoader().getProperties();
    }

    public void talkToSparkConnect() {
        String warehouse = "test_warehouse";
        SparkSession spark = sparkWithWarehouseCalled(
            warehouse,
            AfonProperties.SupportedCloudsEnum.AWS.toString()
        );
        String tableName = warehouse + ".acceptancetests.AwsAccessSpec251117511401"; // aws_trino.acceptancetests.AwsAccessSpec
        log.info("Reading table {}", tableName);
        spark.read().table(tableName).show();
    }

    private SparkSession sparkWithWarehouseCalled(
        String warehouseName,
        String polarisCatalog) {
        SparkWarehouseConfig config = configFactory.getConfig(properties);

        SparkSession.Builder builder = SparkSession
            .builder()
            .classic()
            .appName("test2")
            .remote("sc://localhost:15002/;token=deadbeef");

        config.sparkConfigs(warehouseName, polarisCatalog).entrySet()
              .forEach(entry -> {
                  String key   = entry.getKey();
                  String value = entry.getValue();
                  builder.config(key, value);
                  log.info("{}={}", key, value);
              });

        return builder.getOrCreate();
    }

}
