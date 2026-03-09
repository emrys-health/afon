package health.emrys.afon.infra.checks;

import health.emrys.afon.infra.EmrysCloud;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.spark.SparkWarehouseConfig;
import io.kubernetes.client.openapi.ApiException;
import org.apache.spark.SparkContext;
import org.apache.spark.sql.SparkSession;

import java.io.IOException;

import static health.emrys.afon.spark.SparkWarehouseConfig.SUPPORTED_CLOUD_NAMES;

public class CloudSpark {

    public SparkSession localSparkClusterPolaris() throws IncorrectConfigException, IOException, ApiException {
        return localSparkClusterPolaris(SUPPORTED_CLOUD_NAMES);
    }

    public SparkSession localSparkClusterPolaris(String[] warehouseNames) throws IncorrectConfigException, IOException, ApiException {
        EmrysCloud emrysCloud = new EmrysCloud();
        SparkWarehouseConfig sparkWarehouse = new SparkWarehouseConfig(
            emrysCloud.getAccessToken(),
            emrysCloud.getPolarisId(),
            emrysCloud.getPolarisSecret(),
            emrysCloud.getPolarisHost(),
            "https"
        );
        SparkContext.getOrCreate(sparkWarehouse.sparkConf(4, warehouseNames));
        return SparkSession
            .builder()
            .getOrCreate();
    }

}
