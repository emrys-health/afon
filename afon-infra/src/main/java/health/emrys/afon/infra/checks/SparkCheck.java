package health.emrys.afon.infra.checks;

import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import io.kubernetes.client.openapi.ApiException;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;

import java.io.IOException;

import static health.emrys.afon.polaris.catalogs.ExternalAzureCloudConfigForPolaris.EXTERNAL_AZURE_WAREHOUSE_NAME;
import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

public class SparkCheck {

    private static final Logger logger = getLogger(SparkCheck.class);

    public static void main(String[] args) {
        try {
            SparkCheck app = new SparkCheck();
            app.talkTo(EXTERNAL_AZURE_WAREHOUSE_NAME);
            logger.info("Finished without error");
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    public void talkTo(String warehouse) throws IncorrectConfigException, IOException, ApiException {
        CloudSpark   cloudSpark = new CloudSpark();
        SparkSession spark      = cloudSpark.localSparkClusterPolaris(new String[]{warehouse});
        String       namespaceSql = format("CREATE NAMESPACE IF NOT EXISTS %s.%s", warehouse, "namespaceph");
        logger.info("Executing: " + namespaceSql);
        spark.sql(namespaceSql);
    }

}
