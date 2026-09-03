package health.emrys.afon.ops;

import health.emrys.afon.polaris.config.PolarisCredentials;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.spark.SparkWarehouseConfig;
import health.emrys.afon.spark.SparkWarehouseConfigFactory;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static health.emrys.afon.config.AfonCloud.AZURE;
import static health.emrys.afon.config.AfonCloud.GCP;
import static java.lang.String.format;

/**
 * This generates the PySpark code you can run in Jupyter.
 * <p>
 * If you're messing around the Spark artifact, you might need to presign with:
 * copy it to S3 with:
 * docker run --rm -it  -v ~/.aws:/root/.aws -v afon-iceberg/target/:/tmp/target  amazon/aws-cli s3 cp /tmp/target/afon-iceberg-0.1.jar s3://emrys-afon-bucket/afon-iceberg-0.1.jar
 * then
 * aws s3 presign s3://emrys-afon-bucket/afon-iceberg-0.1.jar --expires-in 14400
 */
public class GeneratePySparkContextMain {

    static final  Logger logger = org.slf4j.LoggerFactory.getLogger(GeneratePySparkContextMain.class);
    private final String polarisId;
    private final String polarisSecret;
    private final String polarisHost;

    private final SparkWarehouseConfig polarisConfig;

    public GeneratePySparkContextMain(AfonCloud cloud, AfonPropertyProvider properties) {
        PolarisCredentials credentials = new PolarisCredentials(cloud, properties);
        polarisId     = credentials.getPolarisId();
        polarisSecret = credentials.getPolarisSecret();
        polarisHost   = credentials.getPolarisHost();
        polarisConfig = SparkWarehouseConfigFactory.getFactory()
                        .createConfig(properties, polarisHost, polarisId, polarisSecret);
    }

    public void generatePySparkCode() {
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
        System.out.println(String.format(
            """
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
        System.out.println(snowflakeSql(AZURE, "smoketesttable260217491351"));
        System.out.println(snowflakeSql(GCP, "smoketesttable260318240929"));

        System.out.println(format(
            """
            -- attempt to get Snowflake to use vended crednetials:
            CREATE OR REPLACE CATALOG INTEGRATION polaris_aws
              CATALOG_SOURCE = POLARIS
              TABLE_FORMAT = ICEBERG
              CATALOG_NAMESPACE = 'acceptancetests'
              REST_CONFIG = (
                CATALOG_URI = 'https://%s/api/catalog/',
                CATALOG_NAME = 'aws'
                ACCESS_DELEGATION_MODE = VENDED_CREDENTIALS
              )
              REST_AUTHENTICATION = (
                    TYPE = OAUTH
                OAUTH_CLIENT_ID = '%s'
                OAUTH_CLIENT_SECRET = '%s'
                OAUTH_ALLOWED_SCOPES = ('PRINCIPAL_ROLE:ALL')
              )
              ENABLED = TRUE;
            """, polarisHost,  polarisId, polarisSecret));
        System.out.println(format("""
                                  CREATE OR REPLACE ICEBERG TABLE my_iceberg_table
                                    CATALOG = 'polaris_aws'
                                    CATALOG_TABLE_NAME = 'AwsAccessSpec251117511401'
                                    CATALOG_NAMESPACE = 'acceptancetests'
                                    REPLACE_INVALID_CHARACTERS = TRUE
                                  """));
        System.out.println("\u001B[0m");
        for (AfonCloud cloud : AfonCloud.values()) {
            Map<String, String> configs = polarisConfig.sparkConfigs(cloud);
            System.out.println("# " + cloud);
            configs.forEach((key, value) -> System.out.println(format(".config('%s', '%s')", key, value)));
        }
    }

    private String snowflakeSql(AfonCloud cloud, String catalog_table) {
        String     catalog    = "iceberg_" + cloud.name();
        String     table      = format("my_%s_table", cloud.name());
        return format(
            """
            CREATE OR REPLACE CATALOG INTEGRATION %s
              CATALOG_SOURCE = ICEBERG_REST
              TABLE_FORMAT = ICEBERG
              REST_CONFIG = (
                CATALOG_URI = 'https://%s/api/catalog/',
                CATALOG_NAME = '%s'
                ACCESS_DELEGATION_MODE = VENDED_CREDENTIALS
              )
              REST_AUTHENTICATION = (
                  TYPE = OAUTH
                  OAUTH_CLIENT_ID = '%s'
                  OAUTH_CLIENT_SECRET = '%s'
                  OAUTH_ALLOWED_SCOPES = ('PRINCIPAL_ROLE:ALL')
                )
              ENABLED = TRUE;
            
            USE DATABASE SNOWFLAKE_LEARNING_DB;
            
            CREATE OR REPLACE ICEBERG TABLE %s
                                             CATALOG = '%s'
                                             CATALOG_TABLE_NAME = '%s'
                                             CATALOG_NAMESPACE = 'acceptancetests'
                                             REPLACE_INVALID_CHARACTERS = TRUE;
            
            select * from %s;
            """, catalog, polarisHost, cloud,
            polarisId, polarisSecret,
            table, catalog, catalog_table, table);
    }

    private @NonNull String azureSmokeTest(String tableName) {
        return format(
            """
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

    private @NonNull String awsSmokeTest(String tableName) {
        return format(
            """
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
            
            spark.sql(f"SELECT * FROM {aws_namespace}.%s").show()
            """, tableName, tableName, tableName);
    }

    private @NonNull String gcpSmokeTest(String tableName) {
        return format(
            """
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

    private void addDependencies(StringBuffer szb, String[] dependencies, String groupId, String version) {
        for (String artifactId : dependencies) {
            szb.append(format("%s:%s:%s,", groupId, artifactId, version));
        }
    }

    private String tableName() {
        LocalDateTime     now       = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyMMddmmHHss");
        String            formatted = now.format(formatter);
        return "smoketesttable" + formatted;
    }

    public static final void main(String[] args) {
        if (args.length == 0) {
            String usage = String.format("""
                                                            
                    Invalid arguments: '%s'
                    usage:  %s requires argument: cloud
                    """, String.join(" ", args), GeneratePySparkContextMain.class.getSimpleName());
            logger.warn(usage);
            return;
        }
        AfonCloud polarisCloud = AfonCloud.valueOf(args[0]);
        var properties = new FilePropertyProvider();
        new GeneratePySparkContextMain(polarisCloud, properties).generatePySparkCode();
    }


}
