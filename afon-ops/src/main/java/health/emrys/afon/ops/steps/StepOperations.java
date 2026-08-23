package health.emrys.afon.ops.steps;

import health.emrys.afon.cdk.aws.AwsFullStack;
import health.emrys.afon.cdk.aws.AwsPolarisStack;
import health.emrys.afon.cdk.azure.AzureFullStack;
import health.emrys.afon.cdk.azure.AzurePolarisStack;
import health.emrys.afon.cdk.gcp.GcpPolarisStack;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.ConfigureKeyCloakUsersMain;
import health.emrys.afon.ops.PurgeAndPopulatePolarisMain;
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
import static health.emrys.afon.ops.spark.SparkChecker.EXTERNAL_NAMESPACE;
import static health.emrys.afon.polaris.PolarisConstants.ACCEPTANCE_TEST_NAMESPACE;
import static java.lang.String.format;

public class StepOperations {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(StepOperations.class);

    private static final Properties NO_ENV_VARIABLES  = new Properties();
    public static final  String     SNOWFLAKE_OUT_DIR = "my_project_folder";

    private final StepActions actions;

    private final AfonPropertyProvider properties;

    public StepOperations(StepActions actions, AfonPropertyProvider properties) {
        this.actions = actions;
        this.properties = properties;
    }

    @NonNull
    public Step deleteStack(final AfonCloud id) {
        return new Step(
            "clean_" + id,
            format("clean the local %s directory (if it exists)", id),
            () -> {
                try {
                    actions.deleteStack(id);
                } catch (Exception e) {
                    if (id == AWS) {
                        logger.error(format(
                            """
                                You may need to run:
                                
                                aws eks update-kubeconfig --region %s --name %s
                                """, properties.getValue(AWS_REGION), properties.getValue(CLUSTER_NAME)), e);

                    }

                }
            });
    }

    public Step deployFullStackIn(AfonCloud id) {
        switch (id) {
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
                throw new RuntimeException("Unsupported id " + id);
        }
    }

    public Step deployPolarisIn(AfonCloud id) {
        switch (id) {
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
                throw new RuntimeException("Unsupported id " + id);
        }
    }

    public Step purgeForeignCatalogFrom(AfonCloud id) {
        switch (id) {
            case AZURE:
                return purgeForeignCatalogFrom(AZURE, properties.getValue(AZURE_DOMAIN_NAME));
            case AWS, GCP:
            default:
                throw new RuntimeException("Unsupported id " + id);
        }
    }

    private @NonNull Step purgeForeignCatalogFrom(AfonCloud catalog, String domainName
    ) {
        return purgePolarisFor(catalog, domainName, EXTERNAL_NAMESPACE, SparkChecker.TABLE);
    }

    private @NonNull Step purgePolarisFor(AfonCloud catalog, String domainName,
                                          String namespace,
                                          String tableName
    ) {
        return new Step(
            format("%s_purge_%s", catalog.toString(), machineNameFor(domainName)),
            format("remove all persisted references to namespace `%s` in Polaris in domain `%s`", namespace, domainName),
            () -> actions.executeMainOf(
                PurgeAndPopulatePolarisMain.class,
                new String[]{domainName, catalog.name(), namespace, tableName},
                NO_ENV_VARIABLES));
    }

    public Step createForeignCatalogIn(AfonCloud id) {
        switch (id) {
            case AZURE:
                Properties properties = new Properties();
                properties.setProperty(
                    AZURE_BUCKET.getName(),
                        this.properties.getValue(AZURE_EXTERNAL_BUCKET));
                return createForeignCatalogIn(AZURE, this.properties.getValue(AZURE_DOMAIN_NAME), properties);
            case AWS, GCP:
            default:
                throw new RuntimeException("Unsupported id " + id);
        }
    }

    private @NonNull Step createCatalogIn(AfonCloud catalog, String domainName) {
        return new Step(
            format("%s_catalog_in_%s", catalog.toString(), machineNameFor(domainName)),
            format("configure a catalog called `%s` in the Polaris in domain `%s`", catalog, domainName),
            () -> actions.createSingleCatalogsFor(domainName, catalog));
    }

    private @NonNull Step createForeignCatalogIn(AfonCloud catalog, String domainName, Properties properties) {
        return new Step(
            format("%s_foreign_catalog_in_%s", catalog.toString(), machineNameFor(domainName)),
            format("Create a foreign catalog called `%s` in the Polaris in domain `%s`", catalog, domainName),
            () -> {
                // swap the buckets
                actions.createSingleCatalogsFor(domainName, catalog, properties);
            });
    }

    public Step initializeAllCatalogsIn(AfonCloud id) {
        switch (id) {
            case AWS:
                return initializeAllCatalogsIn(properties.getValue(AWS_POLARIS_DOMAIN_NAME));
            case AZURE:
                return initializeAllCatalogsIn(properties.getValue(AZURE_DOMAIN_NAME));
            case GCP:
                return initializeAllCatalogsIn(properties.getValue(GCP_DOMAIN_NAME));
            default:
                throw new RuntimeException("Unsupported id " + id);
        }
    }

    @NonNull
    private Step initializeAllCatalogsIn(String domainName) {
        return new Step(
            format("all_catalogs_in_%s", machineNameFor(domainName)),
            format("initialize all the catalogs in Polaris in domain `%s`", domainName),
            () -> {
                try {
                    actions.executeMainOf(
                            PurgeAndPopulatePolarisMain.class,
                        new String[]{domainName},
                        NO_ENV_VARIABLES);
                } catch (Exception e) {
                    logger.error(
                         """
                         Failed to initialize all the catalogs in Polaris.
                         It may be because the certificates are not yet ready. 
                         The hint is an error message such as:
                         
                         PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException:
                         unable to find valid certification path to requested target
                         
                         If this is the case, try re-running this step.                         
                         """, e);
                    throw e;
                }
            });
    }

    public Step waitForPolarisIn(AfonCloud id) {
        Step waitForPolaris  = waitForPolaris(id);
        Step waitForKeyCloak = waitForKeyCloak();
        return waitForPolaris.andThen(waitForKeyCloak);
    }

    private Step waitForPolaris(AfonCloud id) {
        switch (id) {
            case AWS:
                return actions.waitOnDNS(properties.getValue(AWS_POLARIS_DOMAIN_NAME));
            case AZURE:
                return actions.waitOnDNS(properties.getValue(AZURE_DOMAIN_NAME));
            case GCP:
                return actions.waitOnDNS(properties.getValue(GCP_DOMAIN_NAME));
            default:
                throw new RuntimeException("Unsupported id " + id);
        }
    }

    private Step waitForKeyCloak() {
        return actions.waitOnDNS(properties.getValue(KC_HOSTNAME));
    }

    public Step checkAwsSparkWritingTo(AfonCloud id) {
        switch (id) {
            case AWS:
                return awsSparkWritingTo(AWS, EXTERNAL_NAMESPACE);
            case AZURE:
                return awsSparkWritingTo(AZURE, EXTERNAL_NAMESPACE);
            case GCP:
                return awsSparkWritingTo(GCP, EXTERNAL_NAMESPACE);
            default:
                throw new RuntimeException("Unsupported id " + id);
        }
    }

    private Step awsSparkWritingTo(AfonCloud warehouse, String namespace) {
        return sparkTestUsing(
            warehouse,
            format("local Spark call that talks to AWS Polaris, writing a table to %s in namespace %s", warehouse, namespace),
            namespace,
            SparkTestWriter.class,
                properties.getValue(AWS_POLARIS_DOMAIN_NAME),
                properties.getValue(AWS_JUPYTER_DOMAIN));
    }

    public @NonNull Step checkSparkWritingViaAwsPolarisToPolarisIn(AfonCloud id) {
        switch (id) {
            case AZURE:
                return sparkTestUsing(
                    AZURE_EXTERNAL,
                    format("local Spark call that uses %s which defers to %s", properties.getValue(AWS_POLARIS_DOMAIN_NAME), properties.getValue(AZURE_DOMAIN_NAME)),
                    EXTERNAL_NAMESPACE,
                    SparkTestWriter.class,
                        properties.getValue(AWS_POLARIS_DOMAIN_NAME),
                        properties.getValue(AWS_JUPYTER_DOMAIN));
            case AWS, GCP:
            default:
                throw new RuntimeException("Unsupported id " + id);
        }
    }

    public @NonNull Step checkSparkWritingToPolarisIn(AfonCloud id) {
        switch (id) {
            case AZURE:
                return sparkTestUsing(
                    AZURE,
                    format("local Spark call that uses %s", properties.getValue(AZURE_DOMAIN_NAME)),
                    EXTERNAL_NAMESPACE,
                    SparkTestWriter.class,
                        properties.getValue(AZURE_DOMAIN_NAME),
                        properties.getValue(AZURE_JUPYTER_DOMAIN_NAME));
            case AWS, GCP:
            default:
                throw new RuntimeException("Unsupported id " + id);
        }
    }

    public @NonNull Step checkAwsSparkReadingUsing(AfonCloud id) {
        return sparkTestUsing(
            id, format("read via Spark table `%s.%s.%s` by calling `%s`",
                       id, ACCEPTANCE_TEST_NAMESPACE, SparkChecker.TABLE,
                        properties.getValue(AWS_POLARIS_DOMAIN_NAME)),
            ACCEPTANCE_TEST_NAMESPACE,
            SparkTestReader.class,
                properties.getValue(AWS_POLARIS_DOMAIN_NAME),
                properties.getValue(AWS_JUPYTER_DOMAIN));
    }

    private @NonNull Step sparkTestUsing(AfonCloud warehouse,
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
                warehouse.toString()),
            description,
            () -> {
                actions.executeMainOf(
                    ConfigureKeyCloakUsersMain.class,
                    new String[]{domainName, warehouse.toString(), jupyterDomainName},
                    NO_ENV_VARIABLES);

                List<String> args = List.of(
                    warehouse.toString(),
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

    public @NonNull Step snowflakeReadingViaAwsPolaris(AfonCloud warehouse) {
        return new Step(
            "snowflake_reads_" + warehouse.toString(),
            format("Call Snowflake that uses AWS Polaris to get the credentials to read from %s storage", warehouse.name()),
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
                        warehouse.toString(),
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
                        SparkChecker.TABLE,
                        EXTERNAL_NAMESPACE),

                    "SELECT COUNT(*) FROM my_gcp_table_id"
                });
            }
        );
    }

    public @NonNull Step snowflakeWritesTo(AfonCloud id) {
        switch (id) {
            case AZURE:
                return snowflakeWritesToAzure();
            case AWS, GCP:
            default:
                throw new RuntimeException("Unsupported id " + id);
        }
    }

    private Step snowflakeWritesToAzure() {
        LocalDateTime     now        = LocalDateTime.now();
        DateTimeFormatter formatter  = DateTimeFormatter.ofPattern("yy_MM_dd_mm_HH_ss");
        String            formatted  = now.format(formatter);
        return new Step(
            "snowflake_writes_to_azure",
            "Call Snowflake so it writes sample data to Azure blob storage",
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

    public Step purgeFromAwsPolarisAndRecreateFor(AfonCloud id) {
        switch (id) {
            case AZURE:
                return purgePolarisFor(AZURE, properties.getValue(AWS_POLARIS_DOMAIN_NAME), ACCEPTANCE_TEST_NAMESPACE, SparkChecker.TABLE)
                    .andThen(createCatalogIn(AZURE, properties.getValue(AWS_POLARIS_DOMAIN_NAME)));
            case AWS, GCP:
            default:
                throw new RuntimeException("Unsupported id " + id);
        }
    }

    public @NonNull Step registerMostRecentSnowflakeTableInAws(AfonCloud id) {
        switch (id) {
            case AZURE:
                String glob = format(
                    "%s/%s*metadata.json",
                    ACCEPTANCE_TEST_NAMESPACE,
                    SNOWFLAKE_OUT_DIR);
                String json = actions.azureFirstFile(properties, glob, true); // firstFileInLatestDirectoryMatching(glob);
                return registerTableInAwsPolaris(AZURE, json, SparkChecker.TABLE, RegisterPolarisTableMain.class);
            case AWS, GCP:
            default:
                throw new RuntimeException("Unsupported id " + id);
        }
    }

    private Step registerTableInAwsPolaris(AfonCloud warehouse, String jsonMetadataFQN,
                                           String tableName, Class<?> registerTableMainClass
    ) {
        return new Step(
            format("%s_table_in_aws_polaris", warehouse.toString()),
            format("Create `%s` table in the AWS Polaris", warehouse.name()),
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
