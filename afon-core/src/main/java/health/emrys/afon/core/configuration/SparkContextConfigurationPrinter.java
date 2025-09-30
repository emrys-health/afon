package health.emrys.afon.core.configuration;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.comms.AfonPolarisClient;
import health.emrys.afon.spark.SparkWarehouseConfig;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.config.AfonPropertiesLoader.add;
import static java.lang.String.format;

public class SparkContextConfigurationPrinter {

    public static final void main(String[] args) {
        try {
            createSparkConf(args);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void createSparkConf(String[] args) throws IOException {
        SparkContextConfigurationPrinter app = null;
        if (args.length == 0) {
            app = new SparkContextConfigurationPrinter();
        } else if (args.length == 2) {
            AfonPropertiesLoader loader = null;
            if (args.length == 1) {
                loader = new AfonPropertiesLoader();
            } else {
                loader = new AfonPropertiesLoader(args[1]);
            }
            app = createDynamicConfig(args[0], loader);
        }
        app.printSparkConfig();
    }

    private static SparkContextConfigurationPrinter createDynamicConfig(String creds, AfonPropertiesLoader loader) {
        Properties props       = add(creds, loader.getProperties());
        var        client      = new AfonPolarisClient(props);
        String     accessToken = client.getAccessToken();
        return new SparkContextConfigurationPrinter(props, accessToken);
    }

    private final SparkWarehouseConfig polarisConfig;

    public SparkContextConfigurationPrinter(Properties props, String accessToken) {
        polarisConfig = new SparkWarehouseConfig(
                accessToken,
                AfonProperties.POLARIS_ID.getValueFrom(props),
                AfonProperties.POLARIS_SECRET.getValueFrom(props),
                AfonProperties.POLARIS_ENDPOINT.getValueFrom(props)
        );
    }

    public SparkContextConfigurationPrinter() {
        polarisConfig = new SparkWarehouseConfig("{ACCESS_TOKEN}",
                                                 "{CLIENT_ID}",
                                                 "{CLIENT_SECRET}",
                                                 "{POLARIS_HOST}");
    }

    public void printSparkConfig() {

        System.out.println("""
from pyspark.sql import SparkSession

spark = (SparkSession.builder\s
    .appName("practice")\s
    .master("spark://emrys-master-svc:7077")\s
    .config("spark.driver.host", "$DRIVER_HOST")\s
    .config("spark.driver.port", "7078")
                                   """);

        for (AfonProperties.SupportedCloudsEnum cloudProvider : AfonProperties.SupportedCloudsEnum.values()) {
            Map<String, String> configs = polarisConfig.sparkConfigs(cloudProvider);
            for (Map.Entry<String, String> entry : configs.entrySet()) {
                System.out.println(format("    .config(\"%s\", f\"%s\")", entry.getKey(), entry.getValue()));
            }
            System.out.println();
        }
        StringBuffer szb = new StringBuffer("org.apache.hadoop:hadoop-aws:3.4.2,");
        addDependencies(szb, new String[]{"iceberg-aws",}, "org.apache.iceberg", "1.9.0");
        addDependencies(szb, new String[]{"google-cloud-storage",}, "com.google.cloud", "2.57.0");
        addDependencies(szb, new String[]{"s3", "iam-policy-builder", }, "software.amazon.awssdk", "2.31.59");
        System.out.println(format("""
    .config("spark.jars.packages", "%s"
                                   """, szb));
        System.out.println("""
    .getOrCreate()
        )

spark.createDataFrame([["test", 1]], ['name', 'num']).show()
                                   """);
    }

    private static void addDependencies(StringBuffer szb, String[] dependencies, String groupId, String version) {
        for (String artifactId : dependencies) {
            szb.append(format("%s:%s:%s,", groupId, artifactId, version));
        }
    }
}
