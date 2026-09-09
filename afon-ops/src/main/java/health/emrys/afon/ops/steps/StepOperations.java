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
import health.emrys.afon.ops.RegisterPolarisTableMain;
import health.emrys.afon.ops.spark.SparkChecker;
import health.emrys.afon.ops.spark.SparkTestReader;
import health.emrys.afon.ops.spark.SparkTestWriter;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonCloud.*;
import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.ops.ManagePolarisCatalogsMain.DELETE;
import static health.emrys.afon.ops.ManagePolarisCatalogsMain.RECREATE;
import static health.emrys.afon.ops.spark.SparkChecker.EXTERNAL_NAMESPACE;
import static health.emrys.afon.ops.spark.SparkChecker.ACCEPTANCE_TEST_NAMESPACE;
import static java.lang.String.format;

public class StepOperations {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(StepOperations.class);

    private static final Properties NO_ENV_VARIABLES  = new Properties();
    public static final  String     SNOWFLAKE_OUT_DIR = "my_project_folder";

    private final StepActions actions;

    private final AfonPropertyProvider properties;
    private final AfonCatalogs afonCatalogs;

    public StepOperations(StepActions actions, AfonPropertyProvider properties) {
        this.actions = actions;
        this.properties = properties;
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
                return deleteExternalCatalogTableFor(afonCatalogs.azure(), properties.getValue(AZURE_DOMAIN_NAME));
            case AWS, GCP:
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    private @NonNull Step deleteExternalCatalogTableFor(AfonCatalog catalog, String domainName
    ) {
        return deletePolarisTable(catalog, domainName, EXTERNAL_NAMESPACE, SparkChecker.TEST_TABLE);
    }

    private @NonNull Step deletePolarisTable(AfonCatalog catalog,
                                             String domainName,
                                             String namespace,
                                             String tableName
    ) {
        return new Step(
            format("%s_delete_table_%s.%s_for_%s", catalog, namespace, tableName, machineNameFor(domainName)),
            format("delete Polaris table `%s.%s` for domain `%s`", namespace, tableName, domainName),
            () -> actions.executeMainOf(
                ManagePolarisCatalogsMain.class,
                new String[]{DELETE, domainName, catalog.name(), namespace, tableName},
                NO_ENV_VARIABLES));
    }

    public Step createExternalCatalogFor(AfonCloud cloud) {
        switch (cloud) {
            case AZURE:
                Properties properties = new Properties();
                properties.setProperty(
                        AZURE_BUCKET.getName(),
                        this.properties.getValue(AZURE_EXTERNAL_BUCKET));
                return createExternalCatalogFor(afonCatalogs.azure(), this.properties.getValue(AZURE_DOMAIN_NAME), properties);
            case AWS, GCP:
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    private @NonNull Step createCatalogFor(AfonCatalog catalog, String domainName) {
        return new Step(
            format("%s_create_catalog_for_%s", catalog, machineNameFor(domainName)),
            format("create a Polaris catalog `%s` for domain `%s`", catalog, domainName),
            () -> actions.createSingleCatalogsFor(domainName, catalog));
    }

    private @NonNull Step createExternalCatalogFor(AfonCatalog catalog, String domainName, Properties properties) {
        return new Step(
            format("%s_create_external_catalog_for_%s", catalog, machineNameFor(domainName)),
            format("create an external Polaris catalog `%s` for domain `%s`", catalog, domainName),
            () -> {
                // swap the buckets
                actions.createSingleCatalogsFor(domainName, catalog, properties);
            });
    }

    public Step createAllCatalogsFor(AfonCloud cloud) {
        switch (cloud) {
            case AWS:
                return createAllCatalogsFor(properties.getValue(AWS_POLARIS_DOMAIN_NAME));
            case AZURE:
                return createAllCatalogsFor(properties.getValue(AZURE_DOMAIN_NAME));
            case GCP:
                return createAllCatalogsFor(properties.getValue(GCP_DOMAIN_NAME));
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    @NonNull
    private Step createAllCatalogsFor(String domainName) {
        return new Step(
            format("create_all_catalogs_for_%s", machineNameFor(domainName)),
            format("create all Polaris catalogs for domain `%s`", domainName),
            () -> {
                try {
                    actions.executeMainOf(
                            ManagePolarisCatalogsMain.class,
                        new String[]{RECREATE, domainName},
                        NO_ENV_VARIABLES);
                } catch (Exception e) {
                    logger.error(
                         """
                         Failed to delete and recreate all catalogs in Polaris for domain `%s`.
                         It may be because the certificates are not yet ready. 
                         The hint is an error message such as:
                         
                         PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException:
                         unable to find valid certification path to requested target
                         
                         If this is the case, try re-running this step.                         
                         """, domainName, e);
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
                return actions.waitOnDNS(properties.getValue(AZURE_DOMAIN_NAME));
            case GCP:
                return actions.waitOnDNS(properties.getValue(GCP_DOMAIN_NAME));
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    private Step waitForKeyCloak() {
        return actions.waitOnDNS(properties.getValue(KC_HOSTNAME));
    }

    public Step checkAwsSparkWritingTo(AfonCloud cloud) {
        switch (cloud) {
            case AWS:
                return awsSparkWritingTo(afonCatalogs.aws(), EXTERNAL_NAMESPACE);
            case AZURE:
                return awsSparkWritingTo(afonCatalogs.azure(), EXTERNAL_NAMESPACE);
            case GCP:
                return awsSparkWritingTo(afonCatalogs.gcp(), EXTERNAL_NAMESPACE);
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    private Step awsSparkWritingTo(AfonCatalog catalog, String namespace) {
        return sparkTestUsing(
            catalog,
            format("local Spark call that talks to AWS Polaris, writing a table to %s in namespace %s", catalog, namespace),
            namespace,
            SparkTestWriter.class,
                properties.getValue(AWS_POLARIS_DOMAIN_NAME),
                properties.getValue(AWS_JUPYTER_DOMAIN));
    }

    public @NonNull Step checkSparkWritingViaAwsPolarisToPolarisIn(AfonCloud cloud) {
        switch (cloud) {
            case AZURE:
                return sparkTestUsing(
                    afonCatalogs.azureExternal(),
                    format("local Spark call that uses %s which defers to %s", properties.getValue(AWS_POLARIS_DOMAIN_NAME), properties.getValue(AZURE_DOMAIN_NAME)),
                    EXTERNAL_NAMESPACE,
                    SparkTestWriter.class,
                        properties.getValue(AWS_POLARIS_DOMAIN_NAME),
                        properties.getValue(AWS_JUPYTER_DOMAIN));
            case AWS, GCP:
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    public @NonNull Step checkSparkWritingToPolarisIn(AfonCloud cloud) {
        switch (cloud) {
            case AZURE:
                return sparkTestUsing(
                    afonCatalogs.azure(),
                    format("local Spark call that uses %s", properties.getValue(AZURE_DOMAIN_NAME)),
                    EXTERNAL_NAMESPACE,
                    SparkTestWriter.class,
                        properties.getValue(AZURE_DOMAIN_NAME),
                        properties.getValue(AZURE_JUPYTER_DOMAIN_NAME));
            case AWS, GCP:
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    public @NonNull Step checkAwsSparkReadingUsing(AfonCatalog catalog) {
        return sparkTestUsing(
            catalog, format("read via Spark table `%s.%s.%s` by calling `%s`",
                       catalog, ACCEPTANCE_TEST_NAMESPACE, SparkChecker.TEST_TABLE,
                        properties.getValue(AWS_POLARIS_DOMAIN_NAME)),
            ACCEPTANCE_TEST_NAMESPACE,
            SparkTestReader.class,
                properties.getValue(AWS_POLARIS_DOMAIN_NAME),
                properties.getValue(AWS_JUPYTER_DOMAIN));
    }

    private @NonNull Step sparkTestUsing(AfonCatalog catalog,
                                         String description,
                                         String namespace,
                                         Class<? extends SparkChecker> sparkChecker,
                                         String domainName,
                                         String jupyterDomainName
    ) {
        return new Step(
            format(
                "%s_spark_talks_to_%s",
                machineNameFor(domainName),
                catalog),
            description,
            () -> {
                actions.executeMainOf(
                    ConfigureUsersMain.class,
                    new String[]{domainName, catalog.toString(), jupyterDomainName},
                    NO_ENV_VARIABLES);

                List<String> args = List.of(
                    catalog.toString(),
                        properties.getValue(USER_POLARIS_ID),
                        properties.getValue(USER_POLARIS_SECRET),
                    domainName,
                    namespace);
                actions.executeMainOf(
                    sparkChecker,
                    args.toArray(new String[0]),
                    NO_ENV_VARIABLES);
            });
    }

    public @NonNull Step snowflakeReadingViaAwsPolaris(AfonCatalog catalog) {
        return new Step(
            "snowflake_reads_" + catalog.toString(),
            format("Call Snowflake that uses AWS Polaris to get the credentials to read from %s storage", catalog.name()),
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
                        catalog.toString(),
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
                        SparkChecker.TEST_TABLE,
                        EXTERNAL_NAMESPACE),

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
            "call Snowflake so it writes sample data to Azure blob storage",
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
                            """, properties.getValue(AZURE_BUCKET), properties.getValue(AZURE_STORAGE_ACCOUNT), properties.getValue(AZURE_STORAGE_ACCOUNT), properties.getValue(AZURE_BUCKET), properties.getValue(AZURE_TENANT_ID)),

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
                            """, properties.getValue(AZURE_STORAGE_ACCOUNT), properties.getValue(AZURE_BUCKET), ACCEPTANCE_TEST_NAMESPACE, properties.getValue(AZURE_TENANT_ID)),

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
                return deletePolarisTable(afonCatalogs.azure(), properties.getValue(AWS_POLARIS_DOMAIN_NAME), ACCEPTANCE_TEST_NAMESPACE, SparkChecker.TEST_TABLE)
                    .andThen(createCatalogFor(afonCatalogs.azure(), properties.getValue(AWS_POLARIS_DOMAIN_NAME)));
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
                    ACCEPTANCE_TEST_NAMESPACE,
                    SNOWFLAKE_OUT_DIR);
                String json = actions.azureFirstFile(properties, glob, true); // firstFileInLatestDirectoryMatching(glob);
                return registerTableInAwsPolaris(AZURE, json, SparkChecker.TEST_TABLE, RegisterPolarisTableMain.class);
            case AWS, GCP:
            default:
                throw new RuntimeException("Unsupported cloud " + cloud);
        }
    }

    private Step registerTableInAwsPolaris(AfonCloud warehouse, String jsonMetadataFQN,
                                           String tableName, Class<?> registerTableMainClass
    ) {
        return new Step(
            format("%s_register_table_in_aws_polaris", warehouse),
            format("create table `%s` in the AWS Polaris", warehouse.name()),
            () -> {
                actions.registerTable(
                    warehouse,
                    jsonMetadataFQN,
                        properties.getValue(AWS_POLARIS_DOMAIN_NAME),
                    tableName, registerTableMainClass);
            }
        );
    }

    private static @NonNull String machineNameFor(String domainName) {
        String name = domainName;
        if (domainName.contains(".")) {
            name = domainName.substring(0, domainName.indexOf("."));
        }
        return name;
    }

}
