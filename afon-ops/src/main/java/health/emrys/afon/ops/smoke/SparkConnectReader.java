package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.DefaultPropertyProvider;
import health.emrys.afon.spark.connect.SparkConnectClient;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SparkConnectReader {

    private static final Logger logger = LoggerFactory.getLogger(SparkConnectReader.class);

    public static void main(String[] args) {
        AfonPropertyProvider properties = new DefaultPropertyProvider();
        properties.setValue(AfonProperty.POLARIS_ENDPOINT, "westmidspolarisazure.click");
//        SparkWarehouseConfig warehouseConfig = SparkWarehouseConfigFactory.getFactory().createConfig(properties);
        SparkConnectClient   client          = new SparkConnectClient(properties);
        String               warehouse       = "westmids";
        SparkSession         spark           = client.sparkSession(warehouse, "azure");
        Dataset<Row>         df              = spark.read().table(String.format("%s.synthetic_ehr.observations", warehouse));
        df.show();
        logger.info("About to collect...");
        df.collect();
        logger.info("Collected everything");
    }

}
