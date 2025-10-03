package health.emrys.afon.core.configuration;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.infra.ClusterPolarisConfiguration;
import health.emrys.afon.infra.PolarisCredentialGrabber;
import health.emrys.afon.infra.k8s.EksInfoClient;
import health.emrys.afon.polaris.comms.AfonPolarisClient;
import health.emrys.afon.spark.SparkWarehouseConfig;
import io.kubernetes.client.openapi.ApiException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.POLARIS_ENDPOINT;
import static health.emrys.afon.config.AfonPropertiesLoader.add;
import static health.emrys.afon.infra.Defaults.*;
import static java.lang.String.format;

/**
 * You'll need to presign with:
 * copy it to S3 with:
 * docker run --rm -it  -v ~/.aws:/root/.aws -v /home/henryp/Code/Emrys/afon-core/afon-iceberg/target/:/tmp/target  amazon/aws-cli s3 cp /tmp/target/afon-iceberg-0.1.jar s3://emrys-afon-bucket/afon-iceberg-0.1.jar
 * then
 * aws s3 presign s3://emrys-afon-bucket/afon-iceberg-0.1.jar --expires-in 14400
 */
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
        String clusterName        = CLUSTER_NAME;

        AfonPropertiesLoader loader       = new AfonPropertiesLoader();
        Properties           properties   = loader.getProperties();
        var                  eksClient    = new EksInfoClient(clusterName, properties);
        var                  tokenGrabber = new PolarisCredentialGrabber();
        String               accessToken  = null;
        try {
            accessToken = tokenGrabber.getCredentials(eksClient.getLogsOf(POLARIS_POD_NAME));

            ClusterPolarisConfiguration clusterPolaris = new ClusterPolarisConfiguration(clusterName, properties);
            clusterPolaris.addPolarisEndpointToProperties(POLARIS_SERVICE_NAME, eksClient);
            String polarisEndpoint = POLARIS_ENDPOINT.getValueFrom(properties);
            logger.info("Polaris endpoint: {}", polarisEndpoint);

            SparkContextConfigurationPrinter app = createDynamicConfig(accessToken,
                                                                       properties,
                                                                       polarisEndpoint);
            app.printSparkConfig();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static SparkContextConfigurationPrinter createDynamicConfig(String creds,
                                                                        Properties properties,
                                                                        String polarisEndpoint) {
        Properties props       = add(creds, properties);
        var        client      = new AfonPolarisClient(props);
        String     accessToken = client.getAccessToken();
        props.setProperty(POLARIS_ENDPOINT.getPropertyName(), polarisEndpoint);
        return new SparkContextConfigurationPrinter(props, accessToken);
    }

    private final SparkWarehouseConfig polarisConfig;

    public SparkContextConfigurationPrinter(Properties props, String accessToken) {
        polarisConfig = new SparkWarehouseConfig(
                accessToken,
                AfonProperties.POLARIS_ID.getValueFrom(props),
                AfonProperties.POLARIS_SECRET.getValueFrom(props),
                POLARIS_ENDPOINT.getValueFrom(props)
        );
    }

    public void printSparkConfig() {

        System.out.println("""
                                   from pyspark.sql import SparkSession
                                   
                                   spark = (SparkSession.builder\s
                                       .appName("practice")\s
                                       .master("spark://emrys-master-svc:7077")\s
                                       .config("spark.driver.host", "jupyter-internal")\s
                                       .config("spark.driver.port", "7078")
                                   """);

        for (Map.Entry<String, String> entry : polarisConfig.sparkConfigStatics().entrySet()) {
            System.out.println(format("    .config(\"%s\", f\"%s\")", entry.getKey(), entry.getValue()));
        }
        System.out.println();

        for (AfonProperties.SupportedCloudsEnum cloudProvider : AfonProperties.SupportedCloudsEnum.values()) {
            Map<String, String> configs = polarisConfig.sparkConfigs(cloudProvider);
            for (Map.Entry<String, String> entry : configs.entrySet()) {
                System.out.println(format("    .config(\"%s\", f\"%s\")", entry.getKey(), entry.getValue()));
            }
            System.out.println();
        }

        // org.apache.hadoop:hadoop-azure:3.3.4
        /*
: org.apache.spark.SparkException: Job aborted due to stage failure: Task 3 in stage 0.0 failed 4 times, most recent failure: Lost task 3.3 in stage 0.0 (TID 9) (192.168.26.47 executor 2): org.apache.iceberg.exceptions.RuntimeIOException: Failed to get file system for path: abfss://afonstorage@afonstorageaccount.dfs.core.windows.net/acceptancetests/smoketesttable251002171646/data/00003-9-1df1e69c-b012-4ac0-a8ca-017471b846a7-0-00001.parquet
	at org.apache.iceberg.hadoop.Util.getFs(Util.java:57)
...
Caused by: Failure to initialize configuration
	at org.apache.hadoop.fs.azurebfs.services.SimpleKeyProvider.getStorageAccountKey(SimpleKeyProvider.java:51
         */

        StringBuffer szb = new StringBuffer("com.google.guava:guava:33.4.0-jre,org.apache.hadoop:hadoop-aws:3.4.2,");
//        addDependencies(szb, new String[]{"iceberg-aws",}, "org.apache.iceberg", "1.9.0");
        addDependencies(szb, new String[]{"google-cloud-storage",}, "com.google.cloud", "2.57.0");
//        addDependencies(szb, new String[]{"s3", "sts", "iam-policy-builder", }, "software.amazon.awssdk", "2.31.59");
        szb.append("software.amazon.awssdk:bundle:2.34.6,");
        szb.append("org.apache.iceberg:iceberg-spark-runtime-3.5_2.12:1.10.0"); // our docker image uses Spark 2.12
        String tableName = tableName();
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
                                   """);

        System.out.println(format("""
                                          az_namespace = "azure.acceptancetests"
                                          az_fqn = f"{az_namespace}.%s"
                                          
                                          spark.sql(f"CREATE NAMESPACE IF NOT EXISTS {az_namespace}")
                                          print(f"Created namespace {az_namespace}")
                                          
                                          spark.sql(f"CREATE TABLE {az_fqn} (name String, num Integer) USING iceberg")
                                          df_test.writeTo(az_fqn).append()
                                          """, tableName));
        System.out.println(awsSmokeTest(tableName));
        System.out.println(gcpSmokeTest(tableName));

    }

    private static @NotNull String awsSmokeTest(String tableName) {
        return format("""
                              aws_namespace = "aws.acceptancetests"
                              aws_fqn = f"{aws_namespace}.%s"
                              spark.sql(f"CREATE NAMESPACE IF NOT EXISTS {aws_namespace}")
                              print(f"Created namespace {aws_namespace}")
                              
                              df_test.writeTo(aws_fqn).create()
                              print(f"Wrote {aws_fqn} to AWS")
                              """, tableName);
    }

    private static @NotNull String gcpSmokeTest(String tableName) {
        return format("""
                              gcp_namespace = "gcp.acceptancetests"
                              gcp_fqn = f"{gcp_namespace}.%s"
                              spark.sql(f"CREATE NAMESPACE IF NOT EXISTS {gcp_namespace}")
                              print(f"Created namespace {gcp_namespace}")
                              
                              df_test.writeTo(gcp_fqn).create()
                              print(f"Wrote {gcp_fqn} to GCP")
                              """, tableName);
    }

    private static void addDependencies(StringBuffer szb, String[] dependencies, String groupId, String version) {
        for (String artifactId : dependencies) {
            szb.append(format("%s:%s:%s,", groupId, artifactId, version));
        }
    }

    private static final String tableName() {
        LocalDateTime     now       = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyMMddmmHHss");
        String            formatted = now.format(formatter);
        return "smoketesttable" + formatted;
    }
}
