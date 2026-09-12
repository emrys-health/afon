package health.emrys.afon.ops.snowflake;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonPropertyProvider;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static health.emrys.afon.config.AfonProperty.*;
import static java.lang.String.format;

public class SnowflakeStatements {
    public static final  String SNOWFLAKE_OUT_DIR = "my_project_folder";

    public static String[] readingViaAwsPolaris(AfonCatalog catalog, AfonPropertyProvider properties) {
        return new String[]{
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
                        properties.getValue(POLARIS_TABLE_NAME),
                        properties.getValue(POLARIS_NAMESPACE_EXTERNAL)),
                "SELECT COUNT(*) FROM my_gcp_table_id"
        };
    }

    public static String[] writingToAzure(AfonPropertyProvider properties) {
        LocalDateTime now        = LocalDateTime.now();
        DateTimeFormatter formatter  = DateTimeFormatter.ofPattern("yy_MM_dd_mm_HH_ss");
        String            formatted  = now.format(formatter);
        return new String[]{
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
