package health.emrys.afon.ops.spark;

import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.DefaultPropertyProvider;
import health.emrys.afon.spark.SparkWarehouseConfig;
import health.emrys.afon.spark.SparkWarehouseConfigFactory;
import health.emrys.afon.spark.connect.SparkConnectClient;
import org.apache.spark.SparkConf;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

/**
 * SDEs and tables are hard coded but this demonstrates that Spark Connect is slow
 */
public class SparkConnectReader {

    private static final Logger               logger = LoggerFactory.getLogger(SparkConnectReader.class);
    private final        AfonPropertyProvider properties;

    private SparkConnectReader(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    public static void main(String[] args) {
        AfonPropertyProvider properties = new DefaultPropertyProvider();
        properties.setValue(AfonProperty.POLARIS_ENDPOINT, "westmidspolarisazure.click");
        SparkConnectReader app = new SparkConnectReader(properties);
        app.sparkLocalRead("westmids", "azure"); // this is fast
//        app.sparkConnectRead("westmids", "azure"); // this is slow
    }

    /**
     * Run
     * kubectl port-forward pod/spark-connector-0-driver 15002:15002
     * for this.
     */
    public void sparkConnectRead(String warehouse, String catalogName) throws IOException {
        SparkConnectClient client = new SparkConnectClient(properties);
        SparkSession       spark  = client.sparkSession(warehouse, catalogName);
        readTable(warehouse, spark);
    }

    private static void readTable(String warehouse, SparkSession spark) {
        String       fqn = String.format("%s.synthetic_ehr.observations", warehouse);
        Dataset<Row> df  = spark.read().table(fqn);
        df.show();
        logger.info("About to collect...");
        df.collect();
        logger.info("Collected everything");
    }

    private void sparkLocalRead(String warehouse, String catalogName) {
        SparkSession spark = sparkLocal(warehouse, catalogName);
        readTable(warehouse, spark);
    }

    public SparkSession sparkLocal(String warehouse, String catalogName) {
        SparkSession.Builder builder = SparkSession.builder().config(new SparkConf().setMaster("local[2]").setAppName("Tests"));

        final StringBuffer   sb              = new StringBuffer();
        SparkWarehouseConfig warehouseConfig = SparkWarehouseConfigFactory.getFactory().createConfig(properties);
        Map<String, String>  config          = warehouseConfig.sparkConfigs(warehouse, catalogName);
        config.entrySet()
              .forEach(entry -> {
                  String key   = entry.getKey();
                  String value = entry.getValue();
                  builder.config(key, value);
                  sb.append(String.format("%s=%s\n", key, value));
              });
        logger.info("Building SparkSession with config:\n{}", sb);
        return builder.getOrCreate();
    }

}
