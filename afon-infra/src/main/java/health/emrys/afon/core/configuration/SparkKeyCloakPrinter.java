package health.emrys.afon.core.configuration;

import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.spark.SparkWarehouseConfig;
import health.emrys.afon.spark.SparkWarehouseConfigFactory;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.POLARIS_ENDPOINT;
import static java.lang.String.format;

/**
 * Write some PySpark code that can be run in the cloud assuming you're using KeyClaok as te IdP.
 */
public class SparkKeyCloakPrinter {

    static final Logger log = org.slf4j.LoggerFactory.getLogger(SparkKeyCloakPrinter.class);

    public static void main(String[] args) {
        SparkKeyCloakPrinter app         = new SparkKeyCloakPrinter(args[0]);
        String               pySparkCode = app.pySparkTestCode();
        String filename = System.getProperty("java.io.tmpdir") + File.separator + "test.py";
        try {
            try (FileOutputStream fos = new FileOutputStream(filename) ;
                 PrintWriter out = new PrintWriter(fos) ;) {
                out.print(pySparkCode);
            }
        } catch (Exception e) {
            log.error("Could not write to ", filename);
            throw new RuntimeException(e);
        }
    }

    private final SparkWarehouseConfig config;

    SparkKeyCloakPrinter(String polarisHost) {
        Properties                  properties    = new AfonPropertiesLoader().getProperties();
        properties.setProperty(POLARIS_ENDPOINT.getPropertyName(), polarisHost);
        SparkWarehouseConfigFactory configFactory = SparkWarehouseConfigFactory.getFactory();
        config = configFactory.getConfig(properties);
    }

    private String pySparkTestCode() {
        final StringBuffer python = new StringBuffer("""
                           from pyspark.sql import SparkSession
                           
                           spark = (SparkSession.builder.appName("test").remote("sc://spark-connector-0-driver-svc:15002")
                           """);
        for (SupportedCloudsEnum cloud : SupportedCloudsEnum.values()) {
            config.sparkConfigs(cloud).entrySet()
                  .forEach(entry -> {
                      String key   = entry.getKey();
                      String value = entry.getValue();
                      python.append(format(
                          """
                          .config("%s", "%s")
                          """, key, value));
                  });
        }
        python.append(").getOrCreate()\n");
        return python.toString();
    }

}
