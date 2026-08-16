package health.emrys.afon.ops;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.spark.SparkWarehouseConfig;
import health.emrys.afon.spark.SparkWarehouseConfigFactory;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.POLARIS_ENDPOINT;
import static health.emrys.afon.config.AfonCloud.AWS;
import static java.lang.String.format;

/**
 * Write some PySpark code that can be run in the cloud assuming you're using KeyClaok.
 */
public class GeneratePySparkKeyCloakMain {

    static final Logger log = org.slf4j.LoggerFactory.getLogger(GeneratePySparkKeyCloakMain.class);

    private final SparkWarehouseConfig config;

    GeneratePySparkKeyCloakMain(String polarisHost) {
        log.info("Using {} as the Polaris host");
        Properties properties = new FilePropertyProvider().getProperties();
        properties.setProperty(POLARIS_ENDPOINT.getName(), polarisHost);
        SparkWarehouseConfigFactory configFactory = SparkWarehouseConfigFactory.getFactory();
        config = configFactory.getConfig(properties);
    }

    public void generateAndSavePySparkCode() {
        String pySparkCode = pySparkTestCode();
        log.info("pySpark:\n\n{}", pySparkCode);
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
        for (AfonCloud cloud : AfonCloud.values()) {
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

    private @NonNull String toSparkName(AfonCloud cloud) {
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

    public static void main(String[] args) {
        if (args.length > 0) {
            String polarisHost = args[0];
            new GeneratePySparkKeyCloakMain(polarisHost).generateAndSavePySparkCode();
        }
    }


}
