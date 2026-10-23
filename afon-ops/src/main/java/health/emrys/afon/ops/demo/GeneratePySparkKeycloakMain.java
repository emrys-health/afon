package health.emrys.afon.ops.demo;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.FileUserProvider;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.polaris.config.PolarisEndpoint;
import health.emrys.afon.spark.SparkWarehouseConfig;
import health.emrys.afon.spark.SparkWarehouseConfigFactory;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;

import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;
import static java.lang.String.format;

/**
 * Write some PySpark code that can be run in the cloud assuming you're using KeyClaok.
 */
public class GeneratePySparkKeycloakMain {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(GeneratePySparkKeycloakMain.class);

    private final SparkWarehouseConfig config;
    private final AfonCatalogs afonCatalogs;

    GeneratePySparkKeycloakMain(AfonPropertyProvider properties, AfonUserProvider users) {
        SparkWarehouseConfigFactory configFactory = SparkWarehouseConfigFactory.getFactory();
        config = configFactory.createConfig(properties, users);
        afonCatalogs = new AfonCatalogs(properties);
    }

    public void generateAndSavePySparkCode() {
        String pySparkCode = pySparkTestCode();
        logger.info("pySpark:\n\n{}", pySparkCode);
        save(pySparkCode);
    }

    private void save(String pySparkCode) {
        String filename = System.getProperty("java.io.tmpdir") + File.separator + "test.py";
        try {
            try (FileOutputStream fos = new FileOutputStream(filename);
                 PrintWriter out = new PrintWriter(fos);) {
                out.print(pySparkCode);
            }
        } catch (Exception e) {
            logger.error("Could not write to ", filename);
            throw new RuntimeException(e);
        }
    }

    private String pySparkTestCode() {
        Map<String, String> catalogs = new LinkedHashMap<>();
        afonCatalogs.all().forEach(catalog -> catalogs.put(toSparkName(catalog), catalog.toString()));
        return new PySparkSessionCode(config).sessionCode(catalogs) + awsSmokeTest("AwsAccessSpec");
    }

    private @NonNull String toSparkName(AfonCatalog catalog) {
        return "test_" + catalog;
    }

    String awsSmokeTest(String tableName) {
        return format(
            """
            #
            # AWS
            #
            df_read = spark.read.table("%s.acceptancetests.%s")
            df_read.show()
            """, toSparkName(afonCatalogs.aws()), tableName);
    }

    public static void main(String[] args) {
        if (args.length == 0 || args.length > 2) {
            String usage = String.format("""
                                                            
                    Invalid arguments: '%s'
                    usage:  %s requires arguments: cloud [polaris-host]
                    where polaris-host defaults to the Polaris domain name of the cloud
                    """, String.join(" ", args), GeneratePySparkKeycloakMain.class.getSimpleName());
            logger.warn(usage);
            return;
        }
        AfonCloud cloud = AfonCloud.valueOf(args[0].toUpperCase());
        // as seen by the cloud, so that the code gets the token from the KeyCloak of its Polaris
        AfonPropertyProvider properties = new OpsPropertyProvider().forCloud(cloud);
        String polarisHost = args.length == 2 ? args[1] : new PolarisEndpoint(cloud, properties).getEndpoint();
        logger.info("Using {} as the Polaris host", polarisHost);
        properties.setValue(POLARIS_ENDPOINT, polarisHost);
        new GeneratePySparkKeycloakMain(properties, new FileUserProvider()).generateAndSavePySparkCode();
    }


}
