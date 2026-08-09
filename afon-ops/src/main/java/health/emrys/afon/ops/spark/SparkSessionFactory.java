package health.emrys.afon.ops.spark;

import health.emrys.afon.cluster.ClusterCredentials;
import health.emrys.afon.cluster.PolarisClusterCredentials;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.spark.SparkWarehouseConfig;
import health.emrys.afon.spark.SparkWarehouseConfigFactory;
import io.kubernetes.client.openapi.ApiException;
import org.apache.spark.SparkContext;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;

import java.io.IOException;

import static health.emrys.afon.spark.SparkWarehouseConfig.SUPPORTED_CLOUD_NAMES;
import static org.slf4j.LoggerFactory.getLogger;

public class SparkSessionFactory {

    private static final Logger logger = getLogger(SparkSessionFactory.class);

    public static SparkSession localSparkClusterPolaris(AfonCloud polarisCloud) throws IncorrectConfigException, IOException, ApiException {
        return localSparkClusterPolaris(SUPPORTED_CLOUD_NAMES, polarisCloud);
    }

    public static SparkSession localSparkClusterPolaris(String[] warehouseNames,
                                                        AfonCloud polarisCloud)
    throws IOException {
        ClusterCredentials credentials = PolarisClusterCredentials.createClusterCredentials(polarisCloud);
        return new SparkSessionFactory(
            credentials.getPolarisId(),
            credentials.getPolarisSecret(),
            credentials.getPolarisHost(),
            "https",
            warehouseNames
        ).createSparkSession();
    }

    private final SparkWarehouseConfigFactory sparkFactory = SparkWarehouseConfigFactory.getFactory();
    private final String                      polarisId;
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
