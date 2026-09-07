package health.emrys.afon.spark.connect;

import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.spark.SparkWarehouseConfig;
import health.emrys.afon.spark.SparkWarehouseConfigFactory;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;

public class SparkConnect {

    private static final Logger logger = LoggerFactory.getLogger(SparkConnect.class);

    protected final AfonPropertyProvider properties;
    private final AfonCatalogs afonCatalogs;
    private SparkWarehouseConfig sparkConfig;

    public SparkConnect(AfonPropertyProvider properties) {
        this.properties = properties;
        this.afonCatalogs = new AfonCatalogs(properties);
        logger.info("Using Polaris Endpoint {}", properties.getValue(POLARIS_ENDPOINT));
        sparkConfig = SparkWarehouseConfigFactory.getFactory().createConfig(properties);
    }

    public void talkToSparkConnect() {
        String warehouse = "test_warehouse";
        SparkSession spark = sparkWithWarehouseCalled(warehouse, afonCatalogs.aws().toString());
        String tableName = warehouse + ".acceptancetests.AwsAccessSpec"; // aws_trino.acceptancetests.AwsAccessSpec
        logger.info("Reading table {}", tableName);
        // spark.read().table(tableName).show();
        // this works but unlike the line above, running this:
        // kubectl logs -f  spark-connector-0-driver | grep SELECT
        // shows the actual query in Spark Connect's log. Otherwise, grep "scan-report"
        spark.sql(String.format("SELECT * FROM %s WHERE randomInt > -1", tableName)).show();
    }

    private SparkSession sparkWithWarehouseCalled(
        String warehouseName,
        String catalogName
    ) {
        SparkSession.Builder builder = SparkSession
            .builder()
            .classic()
            .appName("test2")
            .remote("sc://localhost:15002/;token=deadbeef");

        final StringBuffer szb = new StringBuffer();
        getWarehouseConfig(warehouseName, catalogName)
            .entrySet()
            .forEach(entry -> {
                String key   = entry.getKey();
                String value = entry.getValue();
                builder.config(key, value);
                szb.append(String.format("%s=%s\n", key, value));
            });
        logger.info("Warehouse Config:\n\n{}", szb);

        return builder.getOrCreate();
    }

    protected Map<String, String> getWarehouseConfig(String warehouseName,
                                                     String catalogName) {
        return sparkConfig.sparkConfigs(warehouseName, catalogName);
    }

    protected String getSparkUser() {
        return sparkConfig.getClientId();
    }

    protected String getSparkPassword() {
        return sparkConfig.getClientSecret();
    }

}
