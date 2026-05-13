package health.emrys.afon.demo;

import com.azure.storage.blob.models.BlobItem;
import health.emrys.afon.cdk.AwsWholeStack;
import health.emrys.afon.cdk.AzureKubernetesPolaris;
import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.infra.PurgeAndPopulatePolaris;
import health.emrys.afon.infra.checks.SparkChecker;
import health.emrys.afon.infra.checks.SparkTestReader;
import health.emrys.afon.infra.checks.SparkTestWriter;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collector;
import java.util.stream.Stream;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.*;
import static health.emrys.afon.infra.checks.SparkChecker.EXTERNAL_NAMESPACE;
import static health.emrys.afon.polaris.PolarisConstants.ACCEPTANCE_TEST_NAMESPACE;
import static java.lang.String.format;
import static java.util.Comparator.comparing;

public class StepOperations {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(StepOperations.class);

    private static final Properties NO_ENV_VARIABLES   = new Properties();
    public static final  String     POLARIS_TABLE_NAME = "sf_table";
    public static final  String     SNOWFLAKE_OUT_DIR  = "my_project_folder";

    private final StepActions actions;

    private final String awsPolarisDomainName;
    private final String awsAccountId;
    private final String region;
    private final String azurePolarisDomainName;
    private final String clusterName;
    private final String gcpPolarisDomainName;

    public StepOperations(StepActions actions) {
        Properties properties = appProperties();
        this.actions                = actions;
        this.awsPolarisDomainName   = AWS_POLARIS_DOMAIN_NAME.getValueFrom(properties);
        this.awsAccountId           = AWS_ACCOUNT_ID.getValueFrom(properties);
        this.region                 = AWS_REGION.getValueFrom(properties);
        this.azurePolarisDomainName = AZURE_DOMAIN_NAME.getValueFrom(properties);
        this.gcpPolarisDomainName   = GCP_DOMAIN_NAME.getValueFrom(properties);
        this.clusterName            = AfonProperties.CLUSTER_NAME.getValueFrom(properties);
    }

    public Step cleanLocalAws() {
        return new Step(
            "clean_aws",
            "clean the local AWS directory (if it exists)",
            () -> {
                try {
                    actions.deleteStack(AwsWholeStack.ID);
                } catch (Exception ex) {
                    log.error(
                        format(
                            """
                            You may need to run:
                            
                            aws eks update-kubeconfig --region %s --name %s
                            """, region, clusterName), ex);
                }
            });
    }

    public Step deployAwsPolaris() {
        return new Step(
            "aws_polaris",
            "deploy Polaris in AWS",
            () -> actions.startAwsPolaris());
    }

    public Step deployAwsSpark() {
        return new Step(
            "aws_spark",
            "deploy Spark in AWS",
            () -> actions.startSparkClusterInAws());
    }

    public Step deployGcpPolaris() {
        return new Step(
            "gcp_polaris",
            "deploy Polaris in GCP",
            () -> actions.startGcpPolaris());
    }

    public Step cleanLocalAzure() {
        return cleanLocal(AzureKubernetesPolaris.ID);
    }

    @NonNull
    public Step cleanLocal(SupportedCloudsEnum id) {
        return new Step(
            "clean_" + id,
            format("clean the local %s directory (if it exists)", id),
            () -> {
                actions.deleteStack(id);
            });
    }

    public Step deployAzurePolaris() {
        return new Step(
            "az_polaris",
            "deploy Polaris in Azure",
            () -> actions.startAzurePolaris());
    }

    public Step purgeForeignCatalogFromAzure() {
        return purgeForeignCatalogFrom(azurePolarisDomainName, AZURE);
    }

    public @NonNull Step purgeForeignCatalogFrom(String domainName,
                                                 SupportedCloudsEnum catalog) {
        return purgeEverythingFor(domainName, catalog, EXTERNAL_NAMESPACE, SparkChecker.TABLE);
    }

    public @NonNull Step purgeEverythingFor(String domainName,
                                            SupportedCloudsEnum catalog,
                                            String namespace,
                                            String tableName) {
        return new Step(
            format("%s_purge_%s", machineNameFrom(domainName), catalog.toString()),
            format("remove all persisted references to %s in Polaris at `%s`", namespace, domainName),
            () -> actions.executeMainOf(
                PurgeAndPopulatePolaris.class,
                new String[]{domainName, catalog.name(), namespace, tableName},
                NO_ENV_VARIABLES));
    }

    private static @NonNull String machineNameFrom(String domainName) {
        String name = domainName;
        if (domainName.contains(".")) {
            name = domainName.substring(0, domainName.indexOf("."));
        }
        return name;
    }

    public Step createAzureCatalogInAzure() {
        return createAzureCatalogWithExternalBucketIn(azurePolarisDomainName);
    }

    public @NonNull Step createAzureCatalogWithExternalBucketIn(String domainName) {
        return new Step(
            "foreign_catalog_in_" + machineNameFrom(domainName),
            format("Create a catalog in the Polaris at `%s`", domainName),
            () -> {
                // swap the buckets
                Properties properties = new Properties();
                properties.setProperty(
                    AZURE_BUCKET.getPropertyName(),
                    AZURE_EXTERNAL_BUCKET.getValueFrom(appProperties()));
                actions.createSingleCatalogsFor(domainName, AZURE, properties);
            });
    }

    private static Properties appProperties() {
        return new AfonPropertiesLoader().getProperties();
    }

    public Step createExternalAzureCatalogInAzure() {
        return createExternalAzureCatalogIn(azurePolarisDomainName);
    }

    public @NonNull Step createAzureCatalogIn(String domainName) {
        return new Step(
            "azure_in_" + machineNameFrom(domainName),
            format("configure a catalog called `%s` in the Polaris at `%s`", AZURE, domainName),
            () -> actions.createSingleCatalogsFor(domainName, AZURE));
    }

    public @NonNull Step createExternalAzureCatalogIn(String domainName) {
        return new Step(
            "external_azure_in_" + machineNameFrom(domainName),
            format("configure a catalog called `%s` in the Polaris at `%s`", AZURE_EXTERNAL, domainName),
            () -> actions.createSingleCatalogsFor(domainName, AZURE_EXTERNAL));
    }

    public Step initializeAllCatalogsInAzure() {
        return initializeAllCatalogsIn(azurePolarisDomainName);
    }

    public Step initializeAllCatalogsInAws() {
        return initializeAllCatalogsIn(awsPolarisDomainName);
    }

    public Step initializeAllCatalogsInGcp() {
        return initializeAllCatalogsIn(gcpPolarisDomainName);
    }

    @NonNull
    public Step initializeAllCatalogsIn(String domainName) {
        return new Step(
            "all_catalogs_in_" + machineNameFrom(domainName),
            "configure all the the catalogs in Polaris at " + domainName,
            () -> actions.executeMainOf(
                PurgeAndPopulatePolaris.class,
                new String[]{domainName},
                NO_ENV_VARIABLES));
    }

    public Step waitForAwsPolaris() {
        return actions.waitFor(awsPolarisDomainName);
    }

    public Step waitForAzurePolaris() {
        return actions.waitFor(azurePolarisDomainName);
    }

    public Step waitForGcpPolaris() {
        return actions.waitFor(gcpPolarisDomainName);
    }

    public Step checkLocalSparkTalksToAzurePolarisViaAwsPolaris() {
        return awsSparkWriteTestUsing(
            AZURE_EXTERNAL,
            format(
                "run Spark locally such that it talks to %s which defers to %s",
                awsPolarisDomainName,
                AZURE_DOMAIN_NAME),
            EXTERNAL_NAMESPACE);
    }

    public Step checkAwsSparkConnectorReadingGcp() {
        return awsSparkTestUsing(GCP, EXTERNAL_NAMESPACE);
    }

    public Step checkAwsSparkConnectorReadingAzure() {
        return awsSparkTestUsing(AZURE, EXTERNAL_NAMESPACE);
    }

    public @NonNull Step awsSparkTestUsing(SupportedCloudsEnum warehouse, String namespace) {
        return awsSparkWriteTestUsing(
            warehouse,
            format(
                "run Spark locally such that it talks to the AWS Polaris, writes a table to %s",
                warehouse),
            namespace);
    }

    private Step awsSparkWriteTestUsing(SupportedCloudsEnum warehouse,
                                        String description,
                                        String namespace) {
        return awsSparkWriteTestUsing(warehouse, description, namespace, SparkTestWriter.class);
    }

    Step awsSparkReadTestUsing(SupportedCloudsEnum warehouse,
                               String namespace) {
        return awsSparkWriteTestUsing(
            warehouse,
            format(
                "read via Spark table `%s.%s.%s`",
                warehouse,
                namespace,
                SparkChecker.TABLE
                  ),
            namespace,
            SparkTestReader.class);
    }

    private @NonNull Step awsSparkWriteTestUsing(SupportedCloudsEnum warehouse,
                                                 String description,
                                                 String namespace,
                                                 Class<? extends SparkChecker> clazz) {
        return new Step(
            format("aws_spark_talks_to_%s", warehouse.toString()),
            description,
            () -> {
                Properties properties = appProperties();
                List<String> args = List.of(
                    warehouse.toString(),
                    POLARIS_ID.getValueFrom(properties),
                    POLARIS_SECRET.getValueFrom(properties),
                    awsPolarisDomainName,
                    namespace);
                actions.executeMainOf(
                    clazz,
                    args.toArray(new String[0]),
                    NO_ENV_VARIABLES);
            });
    }

    public Step snowflakeReadsGcpViaPolaris() {
        return snowflakeReadViaPolaris(GCP);
    }

    public Step snowflakeReadsAzureViaPolaris() {
        return snowflakeReadViaPolaris(AZURE);
    }

    private @NonNull Step snowflakeReadViaPolaris(SupportedCloudsEnum warehouse) {
        return new Step(
            "snowflake_reads_" + warehouse.toString(),
            format("Call Snowflake that uses AWS Polaris to get the credentials to read from %s storage", warehouse.name()),
            () -> {
                Properties properties = appProperties();
                log.warn("Calling Snowflake. Note that some of these statements can take a long time");
                actions.runSnowflakeSql(new String[]{
                    "USE DATABASE SNOWFLAKE_LEARNING_DB",

                    "DROP  ICEBERG TABLE my_gcp_table_id",

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
                        awsPolarisDomainName,
                        warehouse.toString(),
                        POLARIS_ID.getValueFrom(properties),
                        POLARIS_SECRET.getValueFrom(properties)),

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

    public Step snowflakeWritesToAzure() {
        Properties        properties = appProperties();
        String            bucket     = AZURE_BUCKET.getValueFrom(properties);
        String            account    = AZURE_STORAGE_ACCOUNT.getValueFrom(properties);
        String            tenantId   = AZURE_TENANT_ID.getValueFrom(properties);
        LocalDateTime     now        = LocalDateTime.now();
        DateTimeFormatter formatter  = DateTimeFormatter.ofPattern("yy_MM_dd_mm_HH_ss");
        String            formatted  = now.format(formatter);
        return new Step(
            "snowflake_writes_to_azure",
            "Call Snowflake so it writes sample data to Azure blob storage",
            () -> {
                actions.runSnowflakeSql(new String[]{
                    format(
                        """
                        CREATE OR REPLACE STORAGE INTEGRATION azure_int
                          TYPE = EXTERNAL_STAGE
                          STORAGE_PROVIDER = AZURE
                          ENABLED = TRUE
                          STORAGE_ALLOWED_LOCATIONS = ('azure://%s.blob.core.windows.net/%s')
                          AZURE_TENANT_ID='%s';
                        """, bucket, account, account, bucket, tenantId),

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
                        """, account, bucket, ACCEPTANCE_TEST_NAMESPACE, tenantId),

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

                    format("""
                    INSERT INTO my_azure_iceberg_table (value)
                    VALUES
                        ('Alice_%s'),
                        ('Bob_%s');
                    """, formatted, formatted)
                });
            }
        );
    }

    public Step registerMostRecentSnowflakeAzureTableInAws() {
        String glob = format(
            "%s/%s*metadata.json",
            ACCEPTANCE_TEST_NAMESPACE,
            SNOWFLAKE_OUT_DIR);
        String json = firstFile(glob, true); // firstFileInLatestDirectoryMatching(glob);
        return registerTableInAws(json, AZURE, SparkChecker.TABLE);
    }

    public @NonNull Step purgeAzureFromAwsPolarisAndRecreate() {
        String tableName = SparkChecker.TABLE;
        return purgeEverythingFor(awsPolarisDomainName, AZURE, ACCEPTANCE_TEST_NAMESPACE, tableName)
            .andThen(createAzureCatalogIn(awsPolarisDomainName));
    }

    private static @NonNull String firstFile(String glob,
                                             boolean descendingOrder) {
        log.info("Querying Azure for {}", glob);
        AzureReader          lister    = new AzureReader();
        List<BlobItem>       list      = new ArrayList<>(lister.list(glob));
        Comparator<BlobItem> comparing = comparing((BlobItem o) -> o.getProperties().getCreationTime());
        if (descendingOrder) {
            comparing = comparing.reversed();
        }
        BlobItem latest = list
            .stream()
            .sorted(comparing)
            .findFirst()
            .get();
        log.info("Latest blob was created at {}", latest.getProperties().getCreationTime());
        String bucket  = AZURE_BUCKET.getValueFrom(appProperties());
        String account = AZURE_STORAGE_ACCOUNT.getValueFrom(appProperties());
        return format(
            "abfss://%s@%s.dfs.core.windows.net/%s",
            bucket,
            account,
            latest.getName());
    }

    public Step registerTableInAws(String jsonMetadataFQN,
                                   SupportedCloudsEnum warehouse,
                                   String tableName) {
        return new Step(
            format("%s_table_in_aws_polaris", warehouse.toString()),
            format("Create `%s` table in the AWS Polaris", warehouse.name()),
            () -> {
                actions.registerTable(
                    warehouse,
                    jsonMetadataFQN,
                    awsPolarisDomainName,
                    tableName);
            }
        );
    }
}
