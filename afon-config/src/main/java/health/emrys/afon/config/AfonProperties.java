package health.emrys.afon.config;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

public class AfonProperties {

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
    public static final AfonProperty AWS_KEYCLOAK_DNS_ZONE        = new AfonProperty("AWS_KEYCLOAK_DNS_ZONE");
    public static final AfonProperty AWS_KEYCLOAK_CERT_ARN        = new AfonProperty("AWS_KEYCLOAK_CERT_ARN");

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
    public static final AfonProperty KC_HOSTNAME                 = new AfonProperty("KC_HOSTNAME");
    /**
     * Public scheme KeyCloak advertises in its frontend/issuer URLs (http or https). Optional;
     * defaults to https. In production KeyCloak sits behind a TLS-terminating load balancer, so the
     * pod speaks http but must stamp https issuers (which oauth2-proxy requires). Tests set http.
     */
    public static final AfonProperty KC_PROTOCOL                 = new AfonProperty("KC_PROTOCOL");
    public static final AfonProperty KC_BOOTSTRAP_ADMIN_USERNAME = new AfonProperty("KC_BOOTSTRAP_ADMIN_USERNAME");
    public static final AfonProperty KC_BOOTSTRAP_ADMIN_PASSWORD = new AfonProperty("KC_BOOTSTRAP_ADMIN_PASSWORD");
    public static final AfonProperty KC_DB_URL                   = new AfonProperty("KC_DB_URL");
    public static final AfonProperty KC_DB_USERNAME              = new AfonProperty("KC_DB_USERNAME");
    public static final AfonProperty KC_DB_PASSWORD              = new AfonProperty("KC_DB_PASSWORD");
    public static final AfonProperty KC_DB                       = new AfonProperty("KC_DB");

    // Pomerium (zero-trust access proxy; see docs/pomerium-zero-trust-plan.md)
    public static final AfonProperty POMERIUM_AUTHENTICATE_HOSTNAME = new AfonProperty(
        "POMERIUM_AUTHENTICATE_HOSTNAME");
    public static final AfonProperty POMERIUM_VERIFY_HOSTNAME       = new AfonProperty("POMERIUM_VERIFY_HOSTNAME");
    public static final AfonProperty AWS_POMERIUM_CERT_ARN          = new AfonProperty("AWS_POMERIUM_CERT_ARN");
    public static final AfonProperty AWS_POMERIUM_DNS_ZONE          = new AfonProperty("AWS_POMERIUM_DNS_ZONE");
    public static final AfonProperty POMERIUM_CLIENT_ID             = new AfonProperty("POMERIUM_CLIENT_ID");
    public static final AfonProperty POMERIUM_CLIENT_SECRET         = new AfonProperty("POMERIUM_CLIENT_SECRET");
    public static final AfonProperty POMERIUM_SHARED_SECRET         = new AfonProperty("POMERIUM_SHARED_SECRET");
    public static final AfonProperty POMERIUM_COOKIE_SECRET         = new AfonProperty("POMERIUM_COOKIE_SECRET");
    /**
     * KeyCloak group a user must belong to for the example Pomerium policy to allow access to the
     * protected verify route. Optional; defaults to "cluster-admins".
     */
    public static final AfonProperty POMERIUM_ALLOWED_GROUP         = new AfonProperty("POMERIUM_ALLOWED_GROUP");

    // Polaris
    public static final AfonProperty USER_POLARIS_ID     = new AfonProperty("USER_POLARIS_ID");
    public static final AfonProperty USER_POLARIS_SECRET = new AfonProperty("USER_POLARIS_SECRET");
    /**
     * Optional (verified) email for the provisioned Polaris user. Required only if this user logs in
     * through oauth2-proxy, which rejects unverified emails. Defaults to unset.
     */
    public static final AfonProperty USER_POLARIS_EMAIL  = new AfonProperty("USER_POLARIS_EMAIL");
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

    public static Set<String> getAllNames(){
        Set<String> names = new HashSet<>();
        for (Field field : AfonProperties.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                names.add(field.getName());
            }
        }
        return names;
    }
}
