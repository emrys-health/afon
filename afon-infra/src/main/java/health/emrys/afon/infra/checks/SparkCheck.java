package health.emrys.afon.infra.checks;

import health.emrys.afon.polaris.comms.AfonPolarisClient;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;

import static health.emrys.afon.infra.checks.SparkSessionFactory.localSparkClusterPolaris;
import static health.emrys.afon.polaris.catalogs.ExternalAzureCloudConfigForPolaris.EXTERNAL_AZURE_WAREHOUSE_NAME;
import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

public class SparkCheck {

    private static final Logger logger = getLogger(SparkCheck.class);

    public static void main(String[] args) {
        try {
            SparkCheck   app            = new SparkCheck();
            String       warehouse      = EXTERNAL_AZURE_WAREHOUSE_NAME;
            String[]     warehouseNames = {warehouse};
            SparkSession spark          = null;
            if (args.length == 0) {
                spark = localSparkClusterPolaris(warehouseNames);
            } else {
                String            polarisId     = args[0];
                String            polarisSecret = args[1];
                String            polarisHost   = args[2];
                String            protocol      = "http";
                AfonPolarisClient polaris       = new AfonPolarisClient(protocol + "://" + polarisHost, polarisId, polarisSecret);
                SparkSessionFactory factory = new SparkSessionFactory(
                    polaris.getAccessToken(),
                    polarisId,
                    polarisSecret,
                    polarisHost,
                    protocol,
                    warehouseNames
                );
                spark = factory.createSparkSession();
            }
            app.talkTo(spark, warehouse);
            logger.info("Finished without error");
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    public void talkTo(SparkSession spark, String warehouse) {
        String namespaceSql = format("CREATE NAMESPACE IF NOT EXISTS %s.%s", warehouse, "namespaceph");
        logger.info("Executing: " + namespaceSql);
        spark.sql(namespaceSql);
    }

}
