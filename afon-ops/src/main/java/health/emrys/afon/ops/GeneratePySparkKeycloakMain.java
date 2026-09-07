package health.emrys.afon.ops;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.spark.SparkWarehouseConfig;
import health.emrys.afon.spark.SparkWarehouseConfigFactory;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;

import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;
import static java.lang.String.format;

/**
 * Write some PySpark code that can be run in the cloud assuming you're using KeyClaok.
 */
public class GeneratePySparkKeycloakMain {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(GeneratePySparkKeycloakMain.class);

    private final SparkWarehouseConfig config;

    GeneratePySparkKeycloakMain(AfonPropertyProvider properties) {
        SparkWarehouseConfigFactory configFactory = SparkWarehouseConfigFactory.getFactory();
        config = configFactory.createConfig(properties);
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
        final StringBuffer python = new StringBuffer("""
                                                     from pyspark.sql import SparkSession
                                                     import os as os
                                                     
                                                     OIDC_TOKEN = os.environ["OIDC_TOKEN"]
                                                     spark = (SparkSession.builder.appName("test").remote("sc://spark-connector-0-driver-svc:15002")
                                                     """);
        for (AfonCatalog catalog : AfonCatalog.values()) {
            python.append(format("# %s \n", catalog.name()));
            String sparkCatalog = toSparkName(catalog);
            config.sparkConfigs(sparkCatalog, catalog.toString()).entrySet()
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
            """, toSparkName(AfonCatalog.AWS), tableName);
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            String usage = String.format("""
                                                            
                    Invalid arguments: '%s'
                    usage:  %s requires argument: polaris-host
                    """, String.join(" ", args), GeneratePySparkKeycloakMain.class.getSimpleName());
            logger.warn(usage);
            return;
        }
        String polarisHost = args[0];
        logger.info("Using {} as the Polaris host");
        var properties = new FilePropertyProvider();
        properties.setValue(POLARIS_ENDPOINT, polarisHost);
        new GeneratePySparkKeycloakMain(properties).generateAndSavePySparkCode();
    }


}
