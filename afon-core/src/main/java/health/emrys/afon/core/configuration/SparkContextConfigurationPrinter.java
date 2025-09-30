package health.emrys.afon.core.configuration;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.comms.AfonPolarisClient;
import health.emrys.afon.spark.SparkWarehouseConfig;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.config.AfonPropertiesLoader.add;
import static java.lang.String.format;

public class SparkContextConfigurationPrinter {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(SparkContextConfigurationPrinter.class);

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
        } else if (args.length == 2 || args.length == 3) {
            AfonPropertiesLoader loader = null;
            if (args.length == 2) {
                loader = new AfonPropertiesLoader();
            } else {
                loader = new AfonPropertiesLoader(args[2]);
            }
            app = createDynamicConfig(args[0], loader, args[1]);
        } else {
            logger.error("arguements must be: TOKEN POLARIS_ENDPOINT [OVERRIDE_PROPERTIES_FILE]");
        }

        app.printSparkConfig();
    }

    private static SparkContextConfigurationPrinter createDynamicConfig(String creds,
                                                                        AfonPropertiesLoader loader,
                                                                        String polarisEndpoint) {
        Properties props       = add(creds, loader.getProperties());
        var        client      = new AfonPolarisClient(props);
        String     accessToken = client.getAccessToken();
        props.setProperty(AfonProperties.POLARIS_ENDPOINT.getPropertyName(), polarisEndpoint);
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
    .config("spark.driver.host", f"{DRIVER_HOST}")\s
    .config("spark.driver.port", "7078")
                                   """);

        for (AfonProperties.SupportedCloudsEnum cloudProvider : AfonProperties.SupportedCloudsEnum.values()) {
            Map<String, String> configs = polarisConfig.sparkConfigs(cloudProvider);
            for (Map.Entry<String, String> entry : configs.entrySet()) {
                System.out.println(format("    .config(\"%s\", f\"%s\")", entry.getKey(), entry.getValue()));
            }
            System.out.println();
        }
        StringBuffer szb = new StringBuffer("com.google.guava:guava:33.4.0-jre,org.apache.hadoop:hadoop-aws:3.4.2,");
        addDependencies(szb, new String[]{"iceberg-aws",}, "org.apache.iceberg", "1.9.0");
        addDependencies(szb, new String[]{"google-cloud-storage",}, "com.google.cloud", "2.57.0");
        addDependencies(szb, new String[]{"s3", "iam-policy-builder", }, "software.amazon.awssdk", "2.31.59");
        szb.append("org.apache.iceberg:iceberg-spark-runtime-3.5_2.12:1.10.0"); // our docker image uses Spark 2.12
        System.out.println(format("""
    .config("spark.jars.packages", "%s")
    .config("spark.driver.userClassPathFirst", "true")
    .config("spark.executor.userClassPathFirst", "true")
                                   """, szb));
        System.out.println("""
    .getOrCreate()
        )        
print("Setup complete.");

df_test = spark.createDataFrame([["test", 1]], ['name', 'num'])
print("Initialized test data")

gcp_namespace = "gcp.acceptancetests"
gcp_fqn = f"{gcp_namespace}.test_df"
spark.sql(f"CREATE NAMESPACE IF NOT EXISTS {gcp_namespace}")
print("Created namespace")

df_test.writeTo(gcp_fqn).create()
print("Wrote to GCP")
                                   """);
    }

    private static void addDependencies(StringBuffer szb, String[] dependencies, String groupId, String version) {
        for (String artifactId : dependencies) {
            szb.append(format("%s:%s:%s,", groupId, artifactId, version));
        }
    }
}
