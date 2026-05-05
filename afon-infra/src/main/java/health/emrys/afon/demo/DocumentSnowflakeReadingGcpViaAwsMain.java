package health.emrys.afon.demo;

import java.io.IOException;
import java.util.List;

import static java.lang.String.format;

public class DocumentSnowflakeReadingGcpViaAwsMain extends AbstractDocumentMetadata {

    public static final String SNOWFLAKE_TABLE_NAME = "my_gcp_table_id";

    @Override
    public String getSupplementaryIntro() {
        return format("""
               It is assumed that this has been run in Snowflake:
               ```sql
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
            
                USE DATABASE SNOWFLAKE_LEARNING_DB;
            
                CREATE OR REPLACE ICEBERG TABLE %s
                    CATALOG = 'iceberg_gcp_id'
                    CATALOG_TABLE_NAME = 'GcpAccessSpec260430301114'
                    CATALOG_NAMESPACE = 'acceptancetests'
                    REPLACE_INVALID_CHARACTERS = TRUE;
               ```
               """, SNOWFLAKE_TABLE_NAME);
    }

    @Override
    public String getFilename() {
        return "snowflake.md";
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return DeploySnowflakeReadingGcpViaAwsMain.steps(stepOperations);
    }

    public static void main(String[] args) throws IOException {
        new Documenter(new DocumentSnowflakeReadingGcpViaAwsMain()).writeDocumentation();
    }
}
