package health.emrys.afon.core.configuration;

import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.spark.SparkWarehouseConfig;
import health.emrys.afon.spark.SparkWarehouseConfigFactory;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.POLARIS_ENDPOINT;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AWS;
import static java.lang.String.format;

/**
 * Write some PySpark code that can be run in the cloud assuming you're using KeyClaok as te IdP.
 */
public class SparkKeyCloakPrinter {

    static final Logger log = org.slf4j.LoggerFactory.getLogger(SparkKeyCloakPrinter.class);

    public static void main(String[] args) {
        String               polarisHost = args[0];
        SparkKeyCloakPrinter app         = new SparkKeyCloakPrinter(polarisHost);
        app.generateAndSavePySparkCode();
    }

    private final SparkWarehouseConfig config;

    SparkKeyCloakPrinter(String polarisHost) {
        log.info("Using {} as the Polaris host");
        Properties properties = new AfonPropertiesLoader().getProperties();
        properties.setProperty(POLARIS_ENDPOINT.getPropertyName(), polarisHost);
        SparkWarehouseConfigFactory configFactory = SparkWarehouseConfigFactory.getFactory();
        config = configFactory.getConfig(properties);
    }

    private void generateAndSavePySparkCode() {
        String pySparkCode = pySparkTestCode();
        log.info("Config:\n\n{}", pySparkCode);
        save(pySparkCode);
    }

    private static void save(String pySparkCode) {
        String filename = System.getProperty("java.io.tmpdir") + File.separator + "test.py";
        try {
            try (FileOutputStream fos = new FileOutputStream(filename);
                 PrintWriter out = new PrintWriter(fos);) {
                out.print(pySparkCode);
            }
        } catch (Exception e) {
            log.error("Could not write to ", filename);
            throw new RuntimeException(e);
        }
    }

    private String pySparkTestCode() {
        final StringBuffer python = new StringBuffer("""
                                                     from pyspark.sql import SparkSession
                                                     import os as os
                                                     
                                                     OIDC_TOKEN = os.environ["OIDC_TOKEN"]
                                                     spark = (SparkSession.builder.appName("test").remote("sc://spark-connector-0-driver-svc:15002")
                                                     """);
        for (SupportedCloudsEnum cloud : SupportedCloudsEnum.values()) {
            python.append(format("# %s \n", cloud.name()));
            String sparkCatalog = toSparkName(cloud);
            config.sparkConfigs(sparkCatalog, cloud.toString()).entrySet()
                  .forEach(entry -> {
                      String key   = entry.getKey();
                      String value = entry.getValue();
                      if (value.contains(config.getClientId() + ":" + config.getClientSecret())) {
                          python.append(format(
                              """
                              .config("spark.sql.catalog.%s.token", OIDC_TOKEN)
                              """, sparkCatalog, value));
                      } else {
                          python.append(format(
                              """
                              .config("%s", "%s")
                              """, key, value));
                      }
                  });
            python.append("\n");
        }
        python.append("""
                      ).getOrCreate()
                      
                      print("Finished initializing spark session")
                      """);
        python.append(awsSmokeTest("AwsAccessSpec"));
        return python.toString();
    }

    private static @NonNull String toSparkName(SupportedCloudsEnum cloud) {
        return "test_" + cloud;
    }

    String awsSmokeTest(String tableName) {
        return format(
            """
            #
            # AWS
            #
            df_read = spark.read.table("%s.acceptancetests.%s")
            df_read.show()
            """, toSparkName(AWS), tableName);
    }

}
