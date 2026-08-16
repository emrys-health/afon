package health.emrys.afon.ops.spark;

import health.emrys.afon.cluster.ClusterCredentials;
import health.emrys.afon.cluster.PolarisClusterCredentials;
import health.emrys.afon.config.AfonCloud;
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
            Arrays.stream(AfonCloud.values()).map(x -> x.toString()).toArray(String[]::new);

    public static SparkSession localSparkClusterPolaris(AfonCloud polarisCloud) throws IOException {
        ClusterCredentials credentials = new PolarisClusterCredentials(polarisCloud);
        return new SparkSessionFactory(
            credentials.getPolarisId(),
            credentials.getPolarisSecret(),
            credentials.getPolarisHost(),
            "https", WAREHOUSE_NAMES
        ).createSparkSession();
    }

    private final SparkWarehouseConfigFactory sparkFactory = SparkWarehouseConfigFactory.getFactory();
    private final String polarisId;
    private final String polarisSecret;
    private final String polarisHost;
    private final String protocol;
    private final String[] warehouseNames;

    SparkSessionFactory(String polarisId, String polarisSecret, String polarisHost, String protocol, String[] warehouseNames) {
        this.polarisId      = polarisId;
        this.polarisSecret  = polarisSecret;
        this.polarisHost    = polarisHost;
        this.protocol       = protocol;
        this.warehouseNames = warehouseNames;
        logger.info("polarisId = {}, polarisSecret = {}, polarisHost = {}, warehouseNames = {}",
                    polarisId, polarisSecret, polarisHost, warehouseNames);
    }

    public SparkSession createSparkSession() throws IOException {
        SparkWarehouseConfig sparkWarehouse = sparkFactory.getConfig(
            polarisId,
            polarisSecret,
            polarisHost,
            protocol
        );
        SparkContext.getOrCreate(sparkWarehouse.sparkConf(4, warehouseNames));
        return SparkSession
            .builder()
            .getOrCreate();
    }

}
