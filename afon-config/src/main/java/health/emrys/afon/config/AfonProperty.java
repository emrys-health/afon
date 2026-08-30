package health.emrys.afon.config;

import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enum representing each property supported by Afon.   The property then becomes strongly-typed.
 */
public enum AfonProperty {

    CLUSTER_NAME("CLUSTER_NAME"),

    // AWS
    AWS_S3_BUCKET_NAME("AWS_S3_BUCKET_NAME"),
    AWS_S3_BUCKET_NAME_FORBIDDEN_BY_POLARIS("AWS_S3_BUCKET_NAME_FORBIDDEN_BY_POLARIS"),
    AWS_S3_EXTERNAL_BUCKET_NAME("AWS_S3_EXTERNAL_BUCKET_NAME"),
    AWS_S3_BUCKET_NAME_TRINO("AWS_S3_BUCKET_NAME_TRINO"),
    AWS_S3_ROLE_ARN("AWS_S3_ROLE_ARN"),
    GLUE_S3_ROLE_ARN("GLUE_S3_ROLE_ARN"),
    GLUE_CATALOG_NAME("GLUE_CATALOG_NAME"),
    AWS_REGION("AWS_REGION"),
    AWS_ACCESS_KEY_ID("AWS_ACCESS_KEY_ID"),
    AWS_SECRET_ACCESS_KEY("AWS_SECRET_ACCESS_KEY"),
    AWS_S3_BUCKET_NAME_FORBIDDEN("AWS_S3_BUCKET_NAME_FORBIDDEN"),
    AWS_POLARIS_DOMAIN_NAME("AWS_POLARIS_DOMAIN_NAME"),
    AWS_POLARIS_CERT_ARN("AWS_POLARIS_CERT_ARN"),
    AWS_ACCOUNT_ID("AWS_ACCOUNT_ID"),
    AWS_JUPYTER_DOMAIN("AWS_JUPYTER_DOMAIN"),
    AWS_JUPYTER_CERT_ARN("AWS_JUPYTER_CERT_ARN"),
    AWS_POLARIS_DNS_ZONE("AWS_POLARIS_DNS_ZONE"),
    AWS_JUPYTER_DNS_ZONE("AWS_JUPYTER_DNS_ZONE"),
    AWS_KEYCLOAK_DNS_ZONE("AWS_KEYCLOAK_DNS_ZONE"),
    AWS_KEYCLOAK_CERT_ARN("AWS_KEYCLOAK_CERT_ARN"),

    // GCP
    GCP_BUCKET_NAME("GCP_BUCKET_NAME"),
    GCP_BUCKET_NAME_FORBIDDEN("GCP_BUCKET_NAME_FORBIDDEN"),
    GCP_EXTERNAL_BUCKET_NAME("GCP_EXTERNAL_BUCKET_NAME"),
    GCP_SERVICE_ACCOUNT("GCP_SERVICE_ACCOUNT"),
    GOOGLE_APPLICATION_CREDENTIALS("GOOGLE_APPLICATION_CREDENTIALS"),
    GCP_DOMAIN_NAME("GCP_DOMAIN_NAME"),

    // AZURE
    AZURE_SUBSCRIPTION("AZURE_SUBSCRIPTION"),
    AZURE_REGION("AZURE_REGION"),
    AZURE_RESOURCE_GROUP("AZURE_RESOURCE_GROUP"),
    AZURE_RESOURCE_GROUP_ACTION("AZURE_RESOURCE_GROUP_ACTION"),
    AZURE_KEY_VAULT_NAME("AZURE_KEY_VAULT_NAME"),
    AZURE_CA_ID("AZURE_CA_ID"),
    AZURE_CA_PASSWORD("AZURE_CA_PASSWORD"),
    AZURE_DOMAIN_NAME("AZURE_DOMAIN_NAME"),
    AZURE_JUPYTER_DOMAIN_NAME("AZURE_JUPYTER_DOMAIN_NAME"),
    AZURE_BUCKET("AZURE_BUCKET"),
    AZURE_BUCKET_FORBIDDEN("AZURE_BUCKET_FORBIDDEN"),
    AZURE_EXTERNAL_BUCKET("AZURE_EXTERNAL_BUCKET"),
    AZURE_STORAGE_ACCOUNT("AZURE_STORAGE_ACCOUNT"),
    DATABRICKS_TOKEN("DATABRICKS_TOKEN"),

    AZURE_TENANT_ID("AZURE_TENANT_ID"),
    AZURE_CONNECTION_STRING("AZURE_CONNECTION_STRING"),
    AZURE_CLIENT_ID("AZURE_CLIENT_ID"),
    AZURE_CLIENT_SECRET("AZURE_CLIENT_SECRET"),
    AZURE_PROVIDER_REGISTRATION_SKIP("AZURE_PROVIDER_REGISTRATION_SKIP"),

    // Key Cloak
    KC_HOSTNAME("KC_HOSTNAME"),
    /**
     * Public scheme KeyCloak advertises in its frontend/issuer URLs (http or https). Optional;
     * defaults to https. In production KeyCloak sits behind a TLS-terminating load balancer, so the
     * pod speaks http but must stamp https issuers (which oauth2-proxy requires). Tests set http.
     */
    KC_PROTOCOL("KC_PROTOCOL"),
    KC_BOOTSTRAP_ADMIN_USERNAME("KC_BOOTSTRAP_ADMIN_USERNAME"),
    KC_BOOTSTRAP_ADMIN_PASSWORD("KC_BOOTSTRAP_ADMIN_PASSWORD"),
    KC_DB_URL("KC_DB_URL"),
    KC_DB_USERNAME("KC_DB_USERNAME"),
    KC_DB_PASSWORD("KC_DB_PASSWORD"),
    KC_DB("KC_DB"),

    // Polaris
    USER_POLARIS_ID("USER_POLARIS_ID"),
    USER_POLARIS_SECRET("USER_POLARIS_SECRET"),
    /**
     * Optional (verified) email for the provisioned Polaris user. Required only if this user logs in
     * through oauth2-proxy, which rejects unverified emails. Defaults to unset.
     */
    USER_POLARIS_EMAIL("USER_POLARIS_EMAIL"),
    POLARIS_ID("POLARIS_ID"),
    POLARIS_SECRET("POLARIS_SECRET"),
    POLARIS_ENDPOINT("POLARIS_ENDPOINT"),

    OAUTH2_CLIENT_ID("OAUTH2_CLIENT_ID"),
    OAUTH2_CLIENT_SECRET("OAUTH2_CLIENT_SECRET"),
    OAUTH2_COOKIE_SECRET("OAUTH2_COOKIE_SECRET"),

    AZURE_OAUTH2_CLIENT_ID("AZURE_OAUTH2_CLIENT_ID"),
    AZURE_OAUTH2_CLIENT_SECRET("AZURE_OAUTH2_CLIENT_SECRET"),
    AZURE_OAUTH2_COOKIE_SECRET("AZURE_OAUTH2_COOKIE_SECRET"),

    POLARIS_DB_PASSWORD("POLARIS_DB_PASSWORD"),
    POLARIS_DB_HOST("POLARIS_DB_HOST"),

    OIDC_ISSUER_URL("OIDC_ISSUER_URL"),
    OIDC_EMAIL_DOMAIN("OIDC_EMAIL_DOMAIN"),

    JUPYTER_VNC_PASSWORD("JUPYTER_VNC_PASSWORD"),

    // Snowflake
    SNOWFLAKE_JDBC_URL("SNOWFLAKE_JDBC_URL"),
    SNOWFLAKE_PRIVATE_KEY("SNOWFLAKE_PRIVATE_KEY"),

    ALLOWED_DOMAINS_CSV("ALLOWED_DOMAINS_CSV"),
    ;

    private final String name;

    AfonProperty(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public String getValueFrom(Properties properties) {
        String value = properties.getProperty(name);
        if (value == null) {
            throw new IllegalArgumentException("Property " + name + " not found");
        }
        return value;
    }

    /**
     * As {@link #getValueFrom(Properties)} but falls back to {@code defaultValue} when the property is
     * absent, instead of throwing an {@link IllegalArgumentException}.
     * Use for optional properties that have a sensible default.
     */
    public String getValueFrom(Properties properties, String defaultValue) {
        return properties.getProperty(name, defaultValue);
    }

    public static Set<String> getAllNames() {
        return Arrays.stream(values()).map(AfonProperty::getName).collect(Collectors.toSet());
    }

    public String toString() {
        return name;
    }
}
