package health.emrys.afon.infra.checks;

import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.infra.ClusterCredentials;
import health.emrys.afon.infra.PolarisClusterCredentials;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.spark.SparkWarehouseConfig;
import io.kubernetes.client.openapi.ApiException;
import org.apache.spark.SparkContext;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;

import java.io.IOException;

import static health.emrys.afon.spark.SparkWarehouseConfig.SUPPORTED_CLOUD_NAMES;
import static org.slf4j.LoggerFactory.getLogger;

public class SparkSessionFactory {

    private static final Logger logger = getLogger(SparkSessionFactory.class);

    public static SparkSession localSparkClusterPolaris(SupportedCloudsEnum polarisCloud) throws IncorrectConfigException, IOException, ApiException {
        return localSparkClusterPolaris(SUPPORTED_CLOUD_NAMES, polarisCloud);
    }

    public static SparkSession localSparkClusterPolaris(String[] warehouseNames,
                                                        SupportedCloudsEnum polarisCloud)
    throws IOException {
        ClusterCredentials emrysCloud = PolarisClusterCredentials.createClusterCredentials(polarisCloud);
        return new SparkSessionFactory(
            emrysCloud.getAccessToken(),
            emrysCloud.getPolarisId(),
            emrysCloud.getPolarisSecret(),
            emrysCloud.getPolarisHost(),
            "https",
            warehouseNames
        ).createSparkSession();
    }

    private final String accessToken;
    private final String polarisId;
    private final String polarisSecret;
    private final String polarisHost;
    private final String protocol;
    private final String[] warehouseNames;

    SparkSessionFactory(String accessToken, String polarisId, String polarisSecret, String polarisHost, String protocol, String[] warehouseNames) {
        this.accessToken    = accessToken;
        this.polarisId      = polarisId;
        this.polarisSecret  = polarisSecret;
        this.polarisHost    = polarisHost;
        this.protocol       = protocol;
        this.warehouseNames = warehouseNames;
        logger.info("polarisId = {}, polarisSecret = {}, polarisHost = {}, warehouseNames = {}, accessToken = {}",
                    polarisId, polarisSecret, polarisHost, warehouseNames, accessToken);
    }

    public SparkSession createSparkSession() throws IOException {
        SparkWarehouseConfig sparkWarehouse = new SparkWarehouseConfig(
            accessToken,
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
