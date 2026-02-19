package health.emrys.afon.core.configuration;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.infra.EmrysCloud;
import health.emrys.afon.infra.k8s.ClusterState;
import health.emrys.afon.polaris.comms.AfonPolarisClient;
import health.emrys.afon.spark.SparkWarehouseConfig;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.POLARIS_ENDPOINT;
import static health.emrys.afon.config.AfonPropertiesLoader.add;
import static java.lang.String.format;
import static java.lang.String.valueOf;

/**
 * This generates the PySpark code you can run in Jupyter.
 * <p>
 * If you're messing around the Spark artifact, you might need to presign with:
 * copy it to S3 with:
 * docker run --rm -it  -v ~/.aws:/root/.aws -v /home/henryp/Code/Emrys/afon-core/afon-iceberg/target/:/tmp/target  amazon/aws-cli s3 cp /tmp/target/afon-iceberg-0.1.jar s3://emrys-afon-bucket/afon-iceberg-0.1.jar
 * then
 * aws s3 presign s3://emrys-afon-bucket/afon-iceberg-0.1.jar --expires-in 14400
 */
public class SparkContextConfigurationPrinter {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(SparkContextConfigurationPrinter.class);

    public static final void main(String[] args) {
        try {
            var app = new SparkContextConfigurationPrinter(new EmrysCloud());
            app.printSparkConfig();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private final SparkWarehouseConfig polarisConfig;

    public SparkContextConfigurationPrinter(EmrysCloud emrysCloud) {
        polarisConfig = new SparkWarehouseConfig(
                emrysCloud.getAccessToken(),
                emrysCloud.getPolarisId(),
                emrysCloud.getPolarisSecret(),
                emrysCloud.getPolarisHost()
        );
    }

    public SparkWarehouseConfig getPolarisConfig() {
        return polarisConfig;
    }

    public void printSparkConfig() {
        String tableName = tableName();
        System.out.println("\u001B[32m");
        System.out.println("""
                                   from pyspark.sql import SparkSession
                                   
                                   spark = SparkSession.builder.appName("test").remote("sc://spark-connector-0-driver-svc:15002").getOrCreate()
                                   
                                   print("session created")
                                   
                                   df_test = spark.createDataFrame([["test", 1]], ['name', 'num'])
                                   print("Initialized test data")
                                   """);

        System.out.println(awsSmokeTest(tableName));
        System.out.println(gcpSmokeTest(tableName));
        System.out.println(azureSmokeTest(tableName));
        System.out.println(String.format("""
                               df_gcp = spark.read.table(f"{gcp_namespace}.%s")
                               df_azure = spark.read.table(f"{az_namespace}.%s")
                               df_aws = spark.read.table(f"{aws_namespace}.%s")
                               df_joined = (
                                   df_gcp
                                   .join(df_azure, on="name", how="inner")
                                   .join(df_aws, on="name", how="inner")
                               )
                               df_joined.show()
                               """, tableName, tableName, tableName));
        System.out.println("""
                               spark.sql("CREATE NAMESPACE IF NOT EXISTS federatedCatalogName.ph_glue_iceberg_db")
                               df_glue = spark.read.table("federatedCatalogName.ph_glue_iceberg_db.DatumAccTest2")
                               df_glue.show()
                               
                               from datetime import date, datetime
                               df_glue_new = spark.createDataFrame([[1, "label", 2, date(2025, 1, 1), datetime(2025, 1, 1, 10, 30, 0), 42]], ["id", "label", "partitionkey", "date", "timestamp", "randomint"])
                               df_glue_new.writeTo("federatedCatalogName.ph_glue_iceberg_db.DatumAccTest2").append()
                               print("finished")
                               
                               from pyspark.sql.functions import col
                               df_joined = (
                                   df_gcp
                                   .join(df_glue, col("num") == col("id"), how="inner")
                               )
                               df_joined.show()
                               """);

        System.out.println("\u001B[34m");
        System.out.println(format("""
                                          CREATE OR REPLACE CATALOG INTEGRATION polaris_int
                                              CATALOG_SOURCE = POLARIS
                                              TABLE_FORMAT = ICEBERG
                                              REST_CONFIG = (
                                                  CATALOG_URI = 'https://emryspolaris.click/api/catalog/',
                                                  CATALOG_NAME = 'aws'
                                                  )
                                              REST_AUTHENTICATION = (
                                                  TYPE = BEARER
                                                  BEARER_TOKEN = '%s'
                                              )
                                              ENABLED = TRUE;
                                          
                                          CREATE OR REPLACE EXTERNAL VOLUME emrys_s3
                                            STORAGE_LOCATIONS = (
                                              ( NAME = 'eu-west-2'
                                                STORAGE_PROVIDER = 'S3'
                                                STORAGE_BASE_URL = 's3://emrys-afon-bucket/acceptancetests/AwsAccessSpec251117511401/'
                                                STORAGE_AWS_ROLE_ARN = 'arn:aws:iam::418295715997:role/S3ReadWriteRoleSF2'
                                              )
                                            )
                                            ALLOW_WRITES = FALSE;
                                          
                                          USE DATABASE SNOWFLAKE_LEARNING_DB;
                                          
                                          CREATE OR REPLACE ICEBERG TABLE aws_acceptance_Test
                                            EXTERNAL_VOLUME = emrys_s3
                                            CATALOG = polaris_int
                                            CATALOG_NAMESPACE = 'acceptancetests'
                                            CATALOG_TABLE_NAME = 'AwsAccessSpec251117511401'
                                            ;
                                          
                                          SELECT * FROM aws_acceptance_Test;
                                          
                                          DROP TABLE aws_acceptance_Test;
                                          """, polarisConfig.getAccessToken()));

        System.out.println("\u001B[0m");

    }

    private static @NotNull String azureSmokeTest(String tableName) {
        return format("""
                              #
                              # AZURE
                              #
                              az_namespace = "azure.acceptancetests"
                              az_fqn = f"{az_namespace}.%s"
                              
                              spark.sql(f"CREATE NAMESPACE IF NOT EXISTS {az_namespace}")
                              print(f"Created namespace {az_namespace}")
                              
                              spark.sql(f"CREATE TABLE IF NOT EXISTS {az_fqn} (name String, num Integer) USING iceberg")
                              df_test.writeTo(az_fqn).append()
                              
                              df_read = spark.read.table(f"{az_namespace}.%s")
                              df_read.show()
                              """, tableName, tableName);
    }

    private static @NotNull String awsSmokeTest(String tableName) {
        return format("""
                              #
                              # AWS
                              #
                              aws_namespace = "aws.acceptancetests"
                              aws_fqn = f"{aws_namespace}.%s"
                              spark.sql(f"CREATE NAMESPACE IF NOT EXISTS {aws_namespace}")
                              print(f"Created namespace {aws_namespace}")
                              
                              df_test.writeTo(aws_fqn).create()
                              print(f"Wrote {aws_fqn} to AWS")
                              
                              df_read = spark.read.table(f"{aws_namespace}.%s")
                              df_read.show()
                              """, tableName, tableName);
    }

    private static @NotNull String gcpSmokeTest(String tableName) {
        return format("""
                              #
                              # GCP
                              #
                              gcp_namespace = "gcp.acceptancetests"
                              gcp_fqn = f"{gcp_namespace}.%s"
                              spark.sql(f"CREATE NAMESPACE IF NOT EXISTS {gcp_namespace}")
                              print(f"Created namespace {gcp_namespace}")
                              
                              df_test.writeTo(gcp_fqn).create()
                              print(f"Wrote {gcp_fqn} to GCP")
                              
                              df_read = spark.read.table(f"{gcp_namespace}.%s")
                              df_read.show()
                              """, tableName, tableName);
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
