package health.emrys.afon.ops.spark;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.polaris.config.PolarisCredentials;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.spark.SparkWarehouseConfig;
import health.emrys.afon.spark.SparkWarehouseConfigFactory;
import org.apache.spark.SparkContext;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;

import java.io.IOException;

public class SparkSessionFactory {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(SparkSessionFactory.class);

    public static SparkSession createSparkSession(AfonCloud cloud, AfonPropertyProvider properties) throws IOException {
        String[] warehouseNames = new AfonCatalogs(properties).all().stream()
                .map(Object::toString).toArray(String[]::new);
        return createSparkSession(cloud, properties, warehouseNames);
    }

    public static SparkSession createSparkSession(AfonCloud cloud, AfonPropertyProvider properties, String[] warehouseNames) throws IOException {
        PolarisCredentials credentials = new PolarisCredentials(cloud, properties);
        return new SparkSessionFactory(
                properties,
                credentials.getPolarisEndpoint(),
                credentials.getPolarisId(),
                credentials.getPolarisSecret(),
                warehouseNames
        ).createSparkSession();
    }

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

    SparkSession createSparkSession() throws IOException {
        logger.info("SparkSession polarisHost = {}, polarisId = {}, polarisSecret = {}, warehouseNames = {}",
                polarisHost, polarisId, polarisSecret, warehouseNames);
        SparkWarehouseConfig sparkWarehouse = SparkWarehouseConfigFactory.getFactory()
                .createConfig(properties, polarisHost, polarisId, polarisSecret);
        SparkContext.getOrCreate(sparkWarehouse.sparkConf(4, warehouseNames));
        return SparkSession.builder().getOrCreate();
    }

}
