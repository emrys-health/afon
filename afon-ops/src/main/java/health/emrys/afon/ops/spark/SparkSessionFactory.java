package health.emrys.afon.ops.spark;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.polaris.config.PolarisCredentials;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.spark.SparkWarehouseConfig;
import health.emrys.afon.spark.SparkWarehouseConfigFactory;
import org.apache.spark.SparkContext;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Arrays;

public class SparkSessionFactory {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(SparkSessionFactory.class);

    private static final  String[] WAREHOUSE_NAMES =
            Arrays.stream(AfonCatalog.values()).map(x -> x.toString()).toArray(String[]::new);

    public static SparkSession localSparkClusterPolaris(AfonCloud cloud, AfonPropertyProvider properties) throws IOException {
        PolarisCredentials credentials = new PolarisCredentials(cloud, properties);
        return new SparkSessionFactory(
                properties, credentials.getPolarisHost(), credentials.getPolarisId(),
                credentials.getPolarisSecret(),
                WAREHOUSE_NAMES
        ).createSparkSession();
    }

    private final SparkWarehouseConfigFactory sparkFactory = SparkWarehouseConfigFactory.getFactory();
    private final AfonPropertyProvider properties;
    private final String polarisHost;
    private final String polarisId;
    private final String polarisSecret;
    private final String[] warehouseNames;

    SparkSessionFactory(AfonPropertyProvider properties, String polarisHost, String polarisId, String polarisSecret, String[] warehouseNames) {
        this.properties = properties;
        this.polarisHost = polarisHost;
        this.polarisId = polarisId;
        this.polarisSecret = polarisSecret;
        this.warehouseNames = warehouseNames;
    }

    public SparkSession createSparkSession() throws IOException {
        logger.info("SparkSession polarisHost = {}, polarisId = {}, polarisSecret = {}, warehouseNames = {}",
                polarisHost, polarisId, polarisSecret, warehouseNames);
        SparkWarehouseConfig sparkWarehouse = sparkFactory.createConfig(properties, polarisHost, polarisId, polarisSecret);
        SparkContext.getOrCreate(sparkWarehouse.sparkConf(4, warehouseNames));
        return SparkSession
            .builder()
            .getOrCreate();
    }

}
