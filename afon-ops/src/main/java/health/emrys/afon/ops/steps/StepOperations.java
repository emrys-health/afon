package health.emrys.afon.ops.steps;

import health.emrys.afon.cdk.aws.AwsFullStack;
import health.emrys.afon.cdk.aws.AwsPolarisStack;
import health.emrys.afon.cdk.azure.AzureFullStack;
import health.emrys.afon.cdk.azure.AzurePolarisStack;
import health.emrys.afon.cdk.gcp.GcpPolarisStack;
import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.ConfigureUsersMain;
import health.emrys.afon.ops.ManagePolarisCatalogsMain;
import health.emrys.afon.ops.spark.SparkChecker;
import health.emrys.afon.ops.spark.SparkTestReader;
import health.emrys.afon.ops.spark.SparkTestWriter;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

import static health.emrys.afon.config.AfonCloud.*;
import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.ops.ManagePolarisCatalogsMain.DELETE;
import static health.emrys.afon.ops.ManagePolarisCatalogsMain.RECREATE;
import static java.lang.String.format;

public class StepOperations {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(StepOperations.class);

    private static final Properties NO_ENV_VARIABLES  = new Properties();
    public static final  String     SNOWFLAKE_OUT_DIR = "my_project_folder";

    private final StepActions actions;

    private final AfonPropertyProvider properties;
    private final AfonCatalogs afonCatalogs;
    private final String polarisNamespace;
    private final String polarisNamespaceExternal;
    private final String polarisTableName;

    public StepOperations(StepActions actions, AfonPropertyProvider properties) {
        this.actions = actions;
        this.properties = properties;
        this.polarisNamespace = properties.getValue(POLARIS_NAMESPACE);
        this.polarisNamespaceExternal = properties.getValue(POLARIS_NAMESPACE_EXTERNAL);
        this.polarisTableName = properties.getValue(POLARIS_TABLE_NAME);
        this.afonCatalogs = new AfonCatalogs(properties);
    }

    public AfonCatalogs afonCatalogs() {
        return afonCatalogs;
    }

    @NonNull
    public Step deleteStack(final AfonCloud cloud) {
        return new Step(
            "clean_" + cloud,
            format("clean the local %s directory (if it exists)", cloud),
            () -> {
                try {
                    actions.deleteStack(cloud);
                } catch (Exception e) {
                    if (cloud == AWS) {
                        logger.error(format(
                            """
                                You may need to run:
                                
                                aws eks update-kubeconfig --region %s --name %s
                                """, properties.getValue(AWS_REGION), properties.getValue(CLUSTER_NAME)), e);

                    }

                }
            });
    }

    public Step deployFullStackIn(AfonCloud cloud) {
        switch (cloud) {
            case AWS:
                return new Step(
                    "aws_full",
                    "deploy full stack in AWS",
                    () -> actions.startStack(AwsFullStack.class, AWS));
            case AZURE:
                return new Step(
                    "az_full",
                    "deploy full stack in Azure",
                    () -> actions.startStack(AzureFullStack.class, AZURE));
            case GCP:
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    public Step deployPolarisIn(AfonCloud cloud) {
        switch (cloud) {
            case AWS:
                return new Step(
                    "aws_polaris",
                    "deploy Polaris in AWS",
                    () -> actions.startStack(AwsPolarisStack.class, AWS));
            case AZURE:
                return new Step(
                    "azure_polaris",
                    "deploy Polaris in Azure",
                    () -> actions.startStack(AzurePolarisStack.class, AZURE));
            case GCP:
                return new Step(
                    "gcp_polaris",
                    "deploy Polaris in GCP",
                    () -> actions.startStack(GcpPolarisStack.class, GCP));
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    public Step deleteExternalCatalogIn(AfonCloud cloud) {
        switch (cloud) {
            case AZURE:
                return deleteExternalPolarisTableFor(AZURE, afonCatalogs.azure());
            case AWS, GCP:
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    private @NonNull Step deleteExternalPolarisTableFor(AfonCloud cloud, AfonCatalog catalog
    ) {
        return deletePolarisTable(cloud, catalog, polarisNamespaceExternal, polarisTableName);
    }

    private @NonNull Step deletePolarisTable(AfonCloud cloud,
                                             AfonCatalog catalog,
                                             String namespace,
                                             String tableName
    ) {
        return new Step(
            format("delete_table_%s.%s.%s_for_cloud_%s", catalog, namespace, tableName, cloud),
            format("delete Polaris table `%s.%s.%s` for cloud `%s`", catalog, namespace, tableName, cloud),
            () -> actions.executeMainOf(
                ManagePolarisCatalogsMain.class,
                new String[]{DELETE, cloud.toString(), catalog.name(), namespace, tableName},
                NO_ENV_VARIABLES));
    }

    public Step createExternalCatalogFor(AfonCloud cloud) {
        switch (cloud) {
            case AZURE:
                Properties properties = new Properties();
                properties.setProperty(
                        AZURE_BUCKET_NAME.getName(),
                        this.properties.getValue(AZURE_BUCKET_NAME_EXTERNAL));
                return createExternalCatalogFor(AZURE, afonCatalogs.azure(), properties);
            case AWS, GCP:
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    private @NonNull Step createCatalogFor(AfonCloud cloud, AfonCatalog catalog) {
        return new Step(
            format("create_catalog_%s_for_cloud_%s", catalog, cloud),
            format("create Polaris catalog `%s` for cloud `%s`", catalog, cloud),
            () -> actions.createSingleCatalogsFor(cloud, catalog));
    }

    private @NonNull Step createExternalCatalogFor(AfonCloud cloud, AfonCatalog catalog, Properties properties) {
        return new Step(
            format("create_external_catalog_%s_for_cloud_%s", catalog, cloud),
            format("create external Polaris catalog `%s` for cloud `%s`", catalog, cloud),
            () -> {
                // swap the buckets
                actions.createSingleCatalogsFor(cloud, catalog, properties);
            });
    }

    public Step createAllCatalogsFor(AfonCloud cloud) {
        return new Step(
            format("recreate_all_catalogs_for_cloud_%s", cloud),
            format("recreate all Polaris catalogs for cloud `%s`", cloud),
            () -> {
                try {
                    actions.executeMainOf(
                            ManagePolarisCatalogsMain.class,
                        new String[]{RECREATE, cloud.toString()},
                        NO_ENV_VARIABLES);
                } catch (Exception e) {
                    logger.error(
                         """
                         Failed to delete and recreate all catalogs in Polaris for cloud `%s`.
                         It may be because the certificates are not yet ready. 
                         The hint is an error message such as:
                         
                         PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException:
                         unable to find valid certification path to requested target
                         
                         If this is the case, try re-running this step.                         
                         """, cloud, e);
                    throw e;
                }
            });
    }

    public Step waitForPolarisIn(AfonCloud cloud) {
        Step waitForPolaris  = waitForPolaris(cloud);
        Step waitForKeyCloak = waitForKeyCloak();
        return waitForPolaris.andThen(waitForKeyCloak);
    }

    private Step waitForPolaris(AfonCloud cloud) {
        switch (cloud) {
            case AWS:
                return actions.waitOnDNS(properties.getValue(AWS_POLARIS_DOMAIN_NAME));
            case AZURE:
                return actions.waitOnDNS(properties.getValue(AZURE_POLARIS_DOMAIN_NAME));
            case GCP:
                return actions.waitOnDNS(properties.getValue(GCP_POLARIS_DOMAIN_NAME));
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    private Step waitForKeyCloak() {
        return actions.waitOnDNS(properties.getValue(OIDC_DOMAIN_NAME));
    }

    public Step checkAwsSparkWritingTo(AfonCloud cloud) {
        switch (cloud) {
            case AWS:
                return awsSparkWritingTo(afonCatalogs.aws(), polarisNamespaceExternal);
            case AZURE:
                return awsSparkWritingTo(afonCatalogs.azure(), polarisNamespaceExternal);
            case GCP:
                return awsSparkWritingTo(afonCatalogs.gcp(), polarisNamespaceExternal);
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    private Step awsSparkWritingTo(AfonCatalog catalog, String namespace) {
        return sparkTestUsing("Spark writing table",
                AWS, catalog, namespace, polarisTableName, SparkTestWriter.class);
    }

    public @NonNull Step checkAwsSparkDeferringToAzurePolarisHostingExternalCatalog() {
        return sparkTestUsing(format("Spark defers to %s writing table", AZURE),
                AWS, afonCatalogs.azureExternal(), polarisNamespaceExternal, polarisTableName, SparkTestWriter.class);
    }

    public @NonNull Step checkAwsSparkReadingUsing(AfonCatalog catalog) {
        return sparkTestUsing("Spark reading table",
                AWS, catalog, polarisNamespace, polarisTableName, SparkTestReader.class);
    }

    private @NonNull Step sparkTestUsing(String description,
                                         AfonCloud cloud,
                                         AfonCatalog catalog,
                                         String namespace,
                                         String tableName,
                                         Class<? extends SparkChecker> sparkChecker
    ) {
        return new Step(format("%s_spark_talks_to_catalog_%s", cloud, catalog),
                format("%s `%s.%s.%s` on cloud `%s`",
                        description, catalog, namespace, tableName,
                        cloud),
            () -> {
                actions.executeMainOf(
                    ConfigureUsersMain.class,
                    new String[]{cloud.toString(), catalog.toString()},
                    NO_ENV_VARIABLES);

                actions.executeMainOf(
                    sparkChecker,
                    new String[]{cloud.toString(), catalog.toString(), namespace, tableName},
                    NO_ENV_VARIABLES);
            });
    }

    public @NonNull Step snowflakeReadingViaAwsPolaris(AfonCatalog catalog) {
        return new Step(
            "snowflake_reads_catalog_" + catalog.toString(),
            format("Snowflake using AWS Polaris to read catalog %s", catalog.name()),
            () -> {
                logger.warn("Calling Snowflake. Note that some of these statements can take a long time");
                actions.runSnowflakeSql(properties, new String[]{
                    "USE DATABASE SNOWFLAKE_LEARNING_DB",
                    "DROP ICEBERG TABLE my_gcp_table_id",
                    "DROP CATALOG INTEGRATION iceberg_gcp_id",
                    format(
                        """
                            CREATE OR REPLACE CATALOG INTEGRATION iceberg_gcp_id
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
                            """,
                            properties.getValue(AWS_POLARIS_DOMAIN_NAME),
                            catalog,
                            properties.getValue(POLARIS_ID),
                            properties.getValue(POLARIS_SECRET)),
                    format(
                        """              
                            -- NOTE!!!!
                            -- This query can take a minute or two to run
                            -- Please wait... 
                            CREATE OR REPLACE ICEBERG TABLE my_gcp_table_id
                                 CATALOG = 'iceberg_gcp_id'
                                 CATALOG_TABLE_NAME = '%s'
                                 CATALOG_NAMESPACE = '%s'
                                 REPLACE_INVALID_CHARACTERS = TRUE;
                            """,
                        polarisTableName,
                        polarisNamespaceExternal),
                    "SELECT COUNT(*) FROM my_gcp_table_id"
                });
            }
        );
    }

    public @NonNull Step snowflakeWritesTo(AfonCloud cloud) {
        switch (cloud) {
            case AZURE:
                return snowflakeWritesToAzure();
            case AWS, GCP:
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    private Step snowflakeWritesToAzure() {
        LocalDateTime     now        = LocalDateTime.now();
        DateTimeFormatter formatter  = DateTimeFormatter.ofPattern("yy_MM_dd_mm_HH_ss");
        String            formatted  = now.format(formatter);
        return new Step(
            "snowflake_writes_to_azure",
            "Snowflake writes sample data to Azure blob storage",
            () -> {
                actions.runSnowflakeSql(properties, new String[]{
                    format(
                        """
                            CREATE OR REPLACE STORAGE INTEGRATION azure_int
                              TYPE = EXTERNAL_STAGE
                              STORAGE_PROVIDER = AZURE
                              ENABLED = TRUE
                              STORAGE_ALLOWED_LOCATIONS = ('azure://%s.blob.core.windows.net/%s')
                              AZURE_TENANT_ID='%s';
                            """, properties.getValue(AZURE_BUCKET_NAME),
                            properties.getValue(AZURE_STORAGE_ACCOUNT),
                            properties.getValue(AZURE_STORAGE_ACCOUNT),
                            properties.getValue(AZURE_BUCKET_NAME),
                            properties.getValue(AZURE_TENANT_ID)),
                    """
                    DROP ICEBERG TABLE IF EXISTS my_azure_iceberg_table ;
                    """,
                    format(
                        """
                            CREATE OR REPLACE EXTERNAL VOLUME my_external_volume
                              STORAGE_LOCATIONS = (
                                (
                                  NAME = 'azure_loc'
                                  STORAGE_PROVIDER = 'AZURE'
                                  STORAGE_BASE_URL = 'azure://%s.blob.core.windows.net/%s/%s/'
                                  AZURE_TENANT_ID='%s'
                                )
                              );
                            """, properties.getValue(AZURE_STORAGE_ACCOUNT),
                            properties.getValue(AZURE_BUCKET_NAME),
                            polarisNamespace,
                            properties.getValue(AZURE_TENANT_ID)),
                    """
                    USE DATABASE SNOWFLAKE_LEARNING_DB;
                    """,
                    format(
                        """
                            CREATE OR REPLACE ICEBERG TABLE my_azure_iceberg_table (
                                value STRING
                            )
                              EXTERNAL_VOLUME = 'MY_EXTERNAL_VOLUME'
                              CATALOG = 'SNOWFLAKE'
                              BASE_LOCATION = '%s/';
                            """, SNOWFLAKE_OUT_DIR),
                    format(
                        """
                            INSERT INTO my_azure_iceberg_table (value)
                            VALUES
                                ('Alice_%s'),
                                ('Bob_%s');
                            """, formatted, formatted)
                });
            }
        );
    }

    public Step deleteAwsPolarisCatalogAndRecreateFor(AfonCloud cloud) {
        switch (cloud) {
            case AZURE:
                return deletePolarisTable(AWS, afonCatalogs.azure(), polarisNamespace, polarisTableName)
                    .andThen(createCatalogFor(AWS, afonCatalogs.azure()));
            case AWS, GCP:
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    public @NonNull Step registerMostRecentSnowflakeTableInAws(AfonCloud cloud) {
        switch (cloud) {
            case AZURE:
                String glob = format(
                    "%s/%s*metadata.json",
                    polarisNamespace,
                    SNOWFLAKE_OUT_DIR);
                String json = actions.azureFirstFile(properties, glob, true); // firstFileInLatestDirectoryMatching(glob);
                return registerTableInAwsPolaris(afonCatalogs.azure(), polarisNamespace, polarisTableName, json);
            case AWS, GCP:
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    private Step registerTableInAwsPolaris(AfonCatalog catalog,
                                           String namespace,
                                           String tableName,
                                           String metadata
    ) {
        return new Step(
            format("register_table_%s_in_aws_polaris", tableName),
            format("Register table `%s` in the AWS Polaris", tableName),
            () -> {
                actions.registerTable(AWS,
                        catalog,
                        namespace,
                        tableName,
                        metadata
                );
            }
        );
    }

}
