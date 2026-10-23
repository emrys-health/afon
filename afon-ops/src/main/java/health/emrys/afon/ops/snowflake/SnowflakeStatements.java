package health.emrys.afon.ops.snowflake;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.security.keycloak.KeyCloakConfigurer;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.security.SecurityUtils.KEYCLOAK_SCOPE;
import static java.lang.String.format;

public class SnowflakeStatements {
    public static final  String SNOWFLAKE_OUT_DIR = "my_project_folder";

    public static String[] readingViaAwsPolaris(AfonCatalog catalog, AfonPropertyProvider properties) {
        return new String[]{
                "USE DATABASE SNOWFLAKE_LEARNING_DB",
                "DROP ICEBERG TABLE IF EXISTS " + tableName(catalog),
                "DROP CATALOG INTEGRATION IF EXISTS " + integrationName(catalog),
                format(
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
                                      OAUTH_TOKEN_URI = '%s'
                                      OAUTH_CLIENT_ID = '%s'
                                      OAUTH_CLIENT_SECRET = '%s'
                                      OAUTH_ALLOWED_SCOPES = ('%s')
                                  )
                                  ENABLED = TRUE;
                                """,
                        integrationName(catalog),
                        properties.getValue(AWS_POLARIS_DOMAIN_NAME),
                        catalog,
                        oidcTokenEndpoint(properties, AfonCloud.AWS),
                        properties.getValue(POLARIS_ID),
                        properties.getValue(POLARIS_SECRET),
                        KEYCLOAK_SCOPE),
                format(
                        """              
                                -- NOTE!!!!
                                -- This query can take a minute or two to run
                                -- Please wait... 
                                CREATE OR REPLACE ICEBERG TABLE %s
                                     CATALOG = '%s'
                                     CATALOG_TABLE_NAME = '%s'
                                     CATALOG_NAMESPACE = '%s'
                                     REPLACE_INVALID_CHARACTERS = TRUE;
                                """,
                        tableName(catalog),
                        integrationName(catalog),
                        properties.getValue(POLARIS_TABLE_NAME),
                        properties.getValue(POLARIS_NAMESPACE_EXTERNAL)),
                "SELECT COUNT(*) FROM " + tableName(catalog)
        };
    }

    /**
     * @return the name of the catalog integration reading the catalog, one for each catalog so that reading one
     * does not replace the integration of another, eg {@code iceberg_azure_id}
     */
    public static String integrationName(AfonCatalog catalog) {
        return "iceberg_" + identifier(catalog) + "_id";
    }

    /**
     * @return the name of the Iceberg table through which Snowflake reads the catalog, eg {@code my_azure_table_id}
     */
    public static String tableName(AfonCatalog catalog) {
        return "my_" + identifier(catalog) + "_table_id";
    }

    private static String identifier(AfonCatalog catalog) {
        return catalog.id().toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }

    /**
     * The token endpoint of KeyCloak, from which Snowflake gets its token for Polaris: Polaris, which defers to KeyCloak for
     * OAuth, returns HTTP 501 from its own token endpoint, so Snowflake cannot ask it for one.
     *
     * @param cloud the cloud whose Polaris Snowflake reads, and so whose KeyCloak authenticates it
     */
    static String oidcTokenEndpoint(AfonPropertyProvider properties, AfonCloud cloud) {
        return KeyCloakConfigurer.tokenEndpoint(properties.getOidcDomainName(cloud), properties.getValue(OIDC_PROTOCOL, "https"));
    }

    public static String[] writingToAzure(AfonPropertyProvider properties) {
        LocalDateTime now        = LocalDateTime.now();
        DateTimeFormatter formatter  = DateTimeFormatter.ofPattern("yy_MM_dd_mm_HH_ss");
        String            formatted  = now.format(formatter);
        // The "azure" catalog's bucket - no longer a typed AfonProperty (see AZURE_BUCKET_NAME_azure
        // in catalogs-azure.properties), since variant bucket names only ever make sense as configuration.
        String azureBucket = properties.getCatalogProperty("AZURE_BUCKET_NAME", "azure");
        return new String[]{
                format(
                        """
                                CREATE OR REPLACE STORAGE INTEGRATION azure_int
                                  TYPE = EXTERNAL_STAGE
                                  STORAGE_PROVIDER = AZURE
                                  ENABLED = TRUE
                                  STORAGE_ALLOWED_LOCATIONS = ('azure://%s.blob.core.windows.net/%s')
                                  AZURE_TENANT_ID='%s';
                                """, azureBucket,
                        properties.getValue(AZURE_STORAGE_ACCOUNT),
                        properties.getValue(AZURE_STORAGE_ACCOUNT),
                        azureBucket,
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
                        azureBucket,
                        properties.getValue(POLARIS_NAMESPACE),
                        properties.getValue(AZURE_TENANT_ID)),
                """
                    USE DATABASE SNOWFLAKE_LEARNING_DB;
                    """,
                String.format(
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
        };
    }
}
