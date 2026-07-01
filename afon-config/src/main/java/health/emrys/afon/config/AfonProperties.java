package health.emrys.afon.config;

import org.slf4j.Logger;

public class AfonProperties {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(AfonProperties.class);

    /**
     * General details live here
     */
    public static final String PROJECT_PROPERTIES_FILE = "afon.properties";
    /**
     * Sensitive details live in here.
     */
    public static final String ENV_PROPERTIES_FILE     = "env.properties";

    public static final AfonProperty CLUSTER_NAME                 = new AfonProperty("CLUSTER_NAME");
    // AWS
    public static final AfonProperty AWS_S3_BUCKET_NAME           = new AfonProperty("AWS_S3_BUCKET_NAME");
    public static final AfonProperty AWS_S3_EXTERNAL_BUCKET_NAME  = new AfonProperty("AWS_S3_EXTERNAL_BUCKET_NAME");
    public static final AfonProperty AWS_S3_BUCKET_NAME_TRINO     = new AfonProperty("AWS_S3_BUCKET_NAME_TRINO");
    public static final AfonProperty AWS_S3_ROLE_ARN              = new AfonProperty("AWS_S3_ROLE_ARN");
    public static final AfonProperty GLUE_S3_ROLE_ARN             = new AfonProperty("GLUE_S3_ROLE_ARN");
    public static final AfonProperty GLUE_CATALOG_NAME            = new AfonProperty("GLUE_CATALOG_NAME");
    public static final AfonProperty AWS_REGION                   = new AfonProperty("AWS_REGION");
    public static final AfonProperty AWS_ACCESS_KEY_ID            = new AfonProperty("AWS_ACCESS_KEY_ID");
    public static final AfonProperty AWS_SECRET_ACCESS_KEY        = new AfonProperty("AWS_SECRET_ACCESS_KEY");
    public static final AfonProperty AWS_S3_BUCKET_NAME_FORBIDDEN = new AfonProperty("AWS_S3_BUCKET_NAME_FORBIDDEN");
    public static final AfonProperty AWS_POLARIS_DOMAIN_NAME      = new AfonProperty("AWS_POLARIS_DOMAIN_NAME");
    public static final AfonProperty AWS_POLARIS_CERT_ARN         = new AfonProperty("AWS_POLARIS_CERT_ARN");
    public static final AfonProperty AWS_ACCOUNT_ID               = new AfonProperty("AWS_ACCOUNT_ID");
    public static final AfonProperty AWS_JUPYTER_DOMAIN           = new AfonProperty("AWS_JUPYTER_DOMAIN");
    public static final AfonProperty AWS_JUPYTER_CERT_ARN         = new AfonProperty("AWS_JUPYTER_CERT_ARN");
    public static final AfonProperty AWS_POLARIS_DNS_ZONE         = new AfonProperty("AWS_POLARIS_DNS_ZONE");
    public static final AfonProperty AWS_JUPYTER_DNS_ZONE         = new AfonProperty("AWS_JUPYTER_DNS_ZONE");

    // GCP
    public static final AfonProperty GCP_BUCKET_NAME                = new AfonProperty("GCP_BUCKET_NAME");
    public static final AfonProperty GCP_BUCKET_NAME_FORBIDDEN      = new AfonProperty("GCP_BUCKET_NAME_FORBIDDEN");
    public static final AfonProperty GCP_EXTERNAL_BUCKET_NAME       = new AfonProperty("GCP_EXTERNAL_BUCKET_NAME");
    public static final AfonProperty GCP_SERVICE_ACCOUNT            = new AfonProperty("GCP_SERVICE_ACCOUNT");
    public static final AfonProperty GOOGLE_APPLICATION_CREDENTIALS = new AfonProperty(
        "GOOGLE_APPLICATION_CREDENTIALS");
    public static final AfonProperty GCP_DOMAIN_NAME                = new AfonProperty("GCP_DOMAIN_NAME");

    // AZURE
    public static final AfonProperty AZURE_SUBSCRIPTION        = new AfonProperty("AZURE_SUBSCRIPTION");
    public static final AfonProperty AZURE_REGION              = new AfonProperty("AZURE_REGION");
    public static final AfonProperty AZURE_RESOURCE_GROUP      = new AfonProperty("AZURE_RESOURCE_GROUP");
    public static final AfonProperty AZURE_DOMAIN_NAME         = new AfonProperty("AZURE_DOMAIN_NAME");
    public static final AfonProperty AZURE_JUPYTER_DOMAIN_NAME = new AfonProperty("AZURE_JUPYTER_DOMAIN_NAME");
    public static final AfonProperty AZURE_BUCKET              = new AfonProperty("AZURE_BUCKET");
    public static final AfonProperty AZURE_BUCKET_FORBIDDEN    = new AfonProperty("AZURE_BUCKET_FORBIDDEN");
    public static final AfonProperty AZURE_EXTERNAL_BUCKET     = new AfonProperty("AZURE_EXTERNAL_BUCKET");
    public static final AfonProperty AZURE_STORAGE_ACCOUNT     = new AfonProperty("AZURE_STORAGE_ACCOUNT");
    public static final AfonProperty DATABRICKS_TOKEN          = new AfonProperty("DATABRICKS_TOKEN");

    public static final AfonProperty AZURE_TENANT_ID         = new AfonProperty("AZURE_TENANT_ID");
    public static final AfonProperty AZURE_CONNECTION_STRING = new AfonProperty("AZURE_CONNECTION_STRING");
    public static final AfonProperty AZURE_CLIENT_ID         = new AfonProperty("AZURE_CLIENT_ID");
    public static final AfonProperty AZURE_CLIENT_SECRET     = new AfonProperty("AZURE_CLIENT_SECRET");

    // Key Cloak
    public static final AfonProperty KEYCLOAK_HOSTNAME           = new AfonProperty("KEYCLOAK_HOSTNAME");
    public static final AfonProperty KC_BOOTSTRAP_ADMIN_USERNAME = new AfonProperty("KC_BOOTSTRAP_ADMIN_USERNAME");
    public static final AfonProperty KC_BOOTSTRAP_ADMIN_PASSWORD = new AfonProperty("KC_BOOTSTRAP_ADMIN_PASSWORD");
    public static final AfonProperty KC_DB_URL                   = new AfonProperty("KC_DB_URL");
    public static final AfonProperty KC_DB_USERNAME              = new AfonProperty("KC_DB_USERNAME");
    public static final AfonProperty KC_DB_PASSWORD              = new AfonProperty("KC_DB_PASSWORD");
    public static final AfonProperty KC_DB                       = new AfonProperty("KC_DB");

    // Polaris
    public static final AfonProperty USER_POLARIS_ID     = new AfonProperty("USER_POLARIS_ID");
    public static final AfonProperty USER_POLARIS_SECRET = new AfonProperty("USER_POLARIS_SECRET");
    public static final AfonProperty POLARIS_ID          = new AfonProperty("POLARIS_ID");
    public static final AfonProperty POLARIS_SECRET      = new AfonProperty("POLARIS_SECRET");
    public static final AfonProperty POLARIS_ENDPOINT    = new AfonProperty("POLARIS_ENDPOINT");

    public static final AfonProperty OAUTH2_CLIENT_ID     = new AfonProperty("OAUTH2_CLIENT_ID");
    public static final AfonProperty OAUTH2_CLIENT_SECRET = new AfonProperty("OAUTH2_CLIENT_SECRET");
    public static final AfonProperty OAUTH2_COOKIE_SECRET = new AfonProperty("OAUTH2_COOKIE_SECRET");

    public static final AfonProperty AZURE_OAUTH2_CLIENT_ID     = new AfonProperty("AZURE_OAUTH2_CLIENT_ID");
    public static final AfonProperty AZURE_OAUTH2_CLIENT_SECRET = new AfonProperty("AZURE_OAUTH2_CLIENT_SECRET");
    public static final AfonProperty AZURE_OAUTH2_COOKIE_SECRET = new AfonProperty("AZURE_OAUTH2_COOKIE_SECRET");

    public static final AfonProperty POLARIS_DB_PASSWORD = new AfonProperty("POLARIS_DB_PASSWORD");
    public static final AfonProperty POLARIS_DB_HOST     = new AfonProperty("POLARIS_DB_HOST");

    public static final AfonProperty OIDC_ISSUER_URL   = new AfonProperty("OIDC_ISSUER_URL");
    public static final AfonProperty OIDC_EMAIL_DOMAIN = new AfonProperty("OIDC_EMAIL_DOMAIN");

    public static final AfonProperty JUPYTER_VNC_PASSWORD = new AfonProperty("JUPYTER_VNC_PASSWORD");

    // Snowflake
    public static final AfonProperty SNOWFLAKE_JDBC_URL    = new AfonProperty("SNOWFLAKE_JDBC_URL");
    public static final AfonProperty SNOWFLAKE_PRIVATE_KEY = new AfonProperty("SNOWFLAKE_PRIVATE_KEY");

    public enum SupportedCloudsEnum {
        AWS("aws"),
        AWS_FORBIDDEN("aws_forbidden"),
        AWS_TRINO("aws_trino"),
        AZURE_EXTERNAL("azure_external"),
        AZURE_FORBIDDEN("azure_forbidden"),
        AZURE("azure"),
        GCP("gcp"),
        GCP_FORBIDDEN("gcp_forbidden"),
        GCP_EXTERNAL("gcp_external");

        private String value;

        SupportedCloudsEnum(String value) {
            this.value = value;
        }

        public String toString() {
            return String.valueOf(value);
        }
    }
}
