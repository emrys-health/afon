package health.emrys.afon.infra.checks;

import health.emrys.afon.infra.EmrysCloud;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.spark.SparkWarehouseConfig;
import io.kubernetes.client.openapi.ApiException;
import org.apache.spark.SparkContext;
import org.apache.spark.sql.SparkSession;

import java.io.IOException;

import static health.emrys.afon.spark.SparkWarehouseConfig.SUPPORTED_CLOUD_NAMES;

public class SparkSessionFactory {

    public static SparkSession localSparkClusterPolaris() throws IncorrectConfigException, IOException, ApiException {
        return localSparkClusterPolaris(SUPPORTED_CLOUD_NAMES);
    }

    public static SparkSession localSparkClusterPolaris(String[] warehouseNames) throws IncorrectConfigException, IOException, ApiException {
        EmrysCloud emrysCloud = new EmrysCloud();
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
