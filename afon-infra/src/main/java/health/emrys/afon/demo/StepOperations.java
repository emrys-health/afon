package health.emrys.afon.demo;

import ch.vorburger.exec.ManagedProcessException;
import health.emrys.afon.cdk.AwsWholeStack;
import health.emrys.afon.cdk.AzureKubernetesPolaris;
import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.infra.PurgeAndPopulatePolaris;
import health.emrys.afon.infra.checks.SparkCheck;
import health.emrys.afon.snowflake.SmokeTest;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.*;
import static health.emrys.afon.demo.DocumentSnowflakeReadingGcpViaAwsMain.SNOWFLAKE_TABLE_NAME;
import static health.emrys.afon.infra.checks.SparkCheck.EXTERNAL_NAMESPACE;
import static java.lang.String.format;

public class StepOperations {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(StepOperations.class);

    private static final Properties NO_ENV_VARIABLES   = new Properties();
    public static final  String     POLARIS_TABLE_NAME = "sf_table";

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
        String name = machineNameFrom(domainName);
        return new Step(
            name + "_foreign_clean",
            format("remove all persisted references to %s in the Polaris at %s", EXTERNAL_NAMESPACE, domainName),
            () -> actions.executeMainOf(
                PurgeAndPopulatePolaris.class,
                new String[]{domainName, catalog.name(), EXTERNAL_NAMESPACE, SparkCheck.TABLE},
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
        return createAzureCatalogIn(azurePolarisDomainName);
    }

    public @NonNull Step createAzureCatalogIn(String domainName) {
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

    public @NonNull Step createExternalAzureCatalogIn(String domainName) {
        return new Step(
            "external_aws_in_" + machineNameFrom(domainName),
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

    public Step checkLocalSparkTalksToAzureViaAws() {
        return awsSparkSmokeTestUsing(
            AZURE_EXTERNAL,
            format(
                "run Spark locally such that it talks to %s which defers to %s",
                awsPolarisDomainName,
                AZURE_DOMAIN_NAME));
    }

    public Step checkAwsSparkConnectorReadingGcp() {
        return awsSparkSmokeTestUsing(
            GCP,
            format(
                "run Spark locally such that it talks to %s writes a table to %s",
                awsPolarisDomainName,
                GCP));
    }

    private Step awsSparkSmokeTestUsing(SupportedCloudsEnum warehouse,
                                        String description) {
        return new Step(
            format("aws_spark_write_to_%s", warehouse.toString()),
            description,
            () -> {
                Properties properties = appProperties();
                actions.executeMainOf(
                    SparkCheck.class,
                    new String[]{warehouse.toString(),
                                 POLARIS_ID.getValueFrom(properties),
                                 POLARIS_SECRET.getValueFrom(properties),
                                 awsPolarisDomainName
                    },
                    NO_ENV_VARIABLES);
            });
    }

    public Step snowflakeReadsGcpViaPolaris() {
        return new Step(
            "snowflake_reads_gcp",
            "Call Snowflake that uses AWS Polaris to get the credentials to read from GCP storage",
            () -> {
                log.warn("Calling Snowflake. Note that some of these statements can take a long time");
                actions.runSnowflakeSql(new String[]{
                    "USE DATABASE SNOWFLAKE_LEARNING_DB",
                    "DROP  ICEBERG TABLE my_gcp_table_id",
                    "DROP CATALOG INTEGRATION iceberg_gcp_id",
                    """
                    CREATE OR REPLACE CATALOG INTEGRATION iceberg_gcp_id
                      CATALOG_SOURCE = ICEBERG_REST
                      TABLE_FORMAT = ICEBERG
                      REST_CONFIG = (
                          CATALOG_URI = 'https://emryspolaris.click/api/catalog/',
                          CATALOG_NAME = 'gcp'
                          ACCESS_DELEGATION_MODE = VENDED_CREDENTIALS
                      )
                      REST_AUTHENTICATION = (
                          TYPE = OAUTH
                          OAUTH_CLIENT_ID = 'root'
                          OAUTH_CLIENT_SECRET = 's3cr3t'
                          OAUTH_ALLOWED_SCOPES = ('PRINCIPAL_ROLE:ALL')
                      )
                      ENABLED = TRUE;
                    """,
                    format(
                    """                    
                    CREATE OR REPLACE ICEBERG TABLE my_gcp_table_id
                         CATALOG = 'iceberg_gcp_id'
                         CATALOG_TABLE_NAME = '%s'
                         CATALOG_NAMESPACE = '%s'
                         REPLACE_INVALID_CHARACTERS = TRUE;
                    """, SparkCheck.TABLE, EXTERNAL_NAMESPACE),
                    "SELECT COUNT(*) FROM my_gcp_table_id"
                });
            }
        );
    }

    public Step registerGcpTableInAws() {
        return new Step(
            "gcp_table_in_aws_polaris",
            "Create a GCP table in the Polaris of AWS",
            () -> {
                actions.registerTable(
                    GCP,
                    "gs://emrys_afon_core/acceptancetests/GcpAccessSpec260430301114/metadata/00000-ce6be45b-1257-4325" +
                        "-b437-20900bbcf2a3.metadata.json",
                    awsPolarisDomainName,
                    POLARIS_TABLE_NAME
                                     );
            }

        );
    }
}
