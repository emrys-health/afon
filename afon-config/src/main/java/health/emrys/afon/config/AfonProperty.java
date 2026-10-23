package health.emrys.afon.config;

import java.util.Arrays;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enum representing each property supported by Afon.   The property then becomes strongly-typed.
 */
public enum AfonProperty {

    ENV_NAME("ENV_NAME"),
    CLUSTER_NAME("CLUSTER_NAME"),
    DNS_WAIT_TIMEOUT("DNS_WAIT_TIMEOUT"),
    /** Where the tofu output goes, one of "console" (logged and System.out) or "logger" (only logged). Defaults to "logger". */
    TOFU_OUTPUT("TOFU_OUTPUT"),

    /** Selects which theme under afon-theme's {@code themes/<name>/} to deploy. Defaults to "emrys-hdrs". */
    AFON_THEME("AFON_THEME"),

    // AWS
    AWS_REGION("AWS_REGION"),
    AWS_ACCOUNT_ID("AWS_ACCOUNT_ID"),
    AWS_ACCESS_KEY_ID("AWS_ACCESS_KEY_ID"),
    AWS_SECRET_ACCESS_KEY("AWS_SECRET_ACCESS_KEY"),
    AWS_GLUE_ROLE_ARN("AWS_GLUE_ROLE_ARN"),
    AWS_S3_ROLE_ARN("AWS_S3_ROLE_ARN"),
    AWS_POLARIS_DOMAIN_NAME("AWS_POLARIS_DOMAIN_NAME"),
    AWS_JUPYTER_DOMAIN_NAME("AWS_JUPYTER_DOMAIN_NAME"),
    /**
     * Whether the AWS stacks otherwise deploying their own KeyCloak use instead the one at
     * {@code OIDC_DOMAIN_NAME} that the identity stack of the cloud deploys.  Optional: {@code false} when unset.
     */
    AWS_KEYCLOAK_EXTERNAL("AWS_KEYCLOAK_EXTERNAL"),
    /** Elastic IP that all outbound cluster traffic egresses from.  Optional: a new one is allocated when unset. */
    AWS_EGRESS_IP("AWS_EGRESS_IP"),
    AWS_POLARIS_ALLOWED_CIDRS("AWS_POLARIS_ALLOWED_CIDRS"),
    /**
     * S3 bucket that Fluent Bit ships Polaris' console logs to, under {@code polaris/<cluster>/}, so that they
     * outlive the cluster.  Optional: no logs are shipped when unset.
     */
    AWS_POLARIS_LOG_BUCKET("AWS_POLARIS_LOG_BUCKET"),
    /** S3 bucket that Gitea keeps its LFS objects, attachments, packages etc in, under {@code gitea/<cluster>/}. */
    AWS_GITEA_BUCKET("AWS_GITEA_BUCKET"),

    // GCP
    /** Project the GCP stacks are deployed into, eg {@code afon-core}.  Required for the GCP stacks. */
    GCP_PROJECT_ID("GCP_PROJECT_ID"),
    GCP_REGION("GCP_REGION"),
    GCP_SERVICE_ACCOUNT("GCP_SERVICE_ACCOUNT"),
    GCP_USER_ACCOUNT("GCP_USER_ACCOUNT"),
    GOOGLE_APPLICATION_CREDENTIALS("GOOGLE_APPLICATION_CREDENTIALS"),
    GCP_POLARIS_DOMAIN_NAME("GCP_POLARIS_DOMAIN_NAME"),
    GCP_JUPYTER_DOMAIN_NAME("GCP_JUPYTER_DOMAIN_NAME"),
    /**
     * Comma-separated CIDRs admitted to Polaris' nginx ingress, plus the cluster's own addresses.  Optional: Polaris is open to
     * all when unset.
     */
    GCP_POLARIS_ALLOWED_CIDRS("GCP_POLARIS_ALLOWED_CIDRS"),
    /**
     * Address of a regional, external static IP created beforehand in {@link #GCP_PROJECT_ID} and {@link #GCP_REGION}, that all
     * outbound cluster traffic egresses from, through the Cloud NAT.  Optional: Google allocates the addresses when unset.
     */
    GCP_EGRESS_IP("GCP_EGRESS_IP"),
    /**
     * Whether the Polaris TLS cert cached in Secret Manager is restored on deploy.  Defaults to false.
     * A plain switch, unlike {@link #AZURE_KEY_VAULT_NAME}: Secret Manager has no named vault, its secrets live
     * directly in the project {@link #GCP_PROJECT_ID}.
     */
    GCP_SECRETS_ENABLED("GCP_SECRETS_ENABLED"),
    /** GCS bucket that Gitea keeps its LFS objects, attachments, packages etc in, under {@code gitea/<cluster>/}. */
    GCP_GITEA_BUCKET("GCP_GITEA_BUCKET"),
    /**
     * HMAC key with which Gitea reaches {@link #GCP_GITEA_BUCKET}: Gitea has no GCS client of its own, so it goes
     * through GCS' S3 compatible API, which only takes HMAC keys.
     */
    GCP_GITEA_HMAC_ACCESS_ID("GCP_GITEA_HMAC_ACCESS_ID"),
    GCP_GITEA_HMAC_SECRET("GCP_GITEA_HMAC_SECRET"),

    // AZURE
    AZURE_SUBSCRIPTION("AZURE_SUBSCRIPTION"),
    AZURE_REGION("AZURE_REGION"),
    AZURE_RESOURCE_GROUP("AZURE_RESOURCE_GROUP"),
    AZURE_KEY_VAULT_NAME("AZURE_KEY_VAULT_NAME"),
    AZURE_CA_ID("AZURE_CA_ID"),
    AZURE_CA_PASSWORD("AZURE_CA_PASSWORD"),
    AZURE_POLARIS_DOMAIN_NAME("AZURE_POLARIS_DOMAIN_NAME"),
    AZURE_JUPYTER_DOMAIN_NAME("AZURE_JUPYTER_DOMAIN_NAME"),
    AZURE_POLARIS_ALLOWED_CIDRS("AZURE_POLARIS_ALLOWED_CIDRS"),
    /**
     * Public IP address, of a Standard, static public IP created beforehand in {@link #AZURE_RESOURCE_GROUP}, that all outbound
     * cluster traffic egresses from.  Optional: Azure allocates one when unset.
     */
    AZURE_EGRESS_IP("AZURE_EGRESS_IP"),
    AZURE_STORAGE_ACCOUNT("AZURE_STORAGE_ACCOUNT"),
    /**
     * Blob container, of the storage account of {@link #AZURE_CONNECTION_STRING}, that Gitea keeps its LFS objects,
     * attachments, packages etc in, under {@code gitea/<cluster>/}.
     */
    AZURE_GITEA_BUCKET("AZURE_GITEA_BUCKET"),
    AZURE_DATABRICKS_TOKEN("AZURE_DATABRICKS_TOKEN"),

    AZURE_GRANT_ROLES("AZURE_GRANT_ROLES"),
    AZURE_TENANT_ID("AZURE_TENANT_ID"),
    AZURE_APP_ID("AZURE_APP_ID"),
    AZURE_APP_SECRET("AZURE_APP_SECRET"),
    AZURE_SP_ID("AZURE_SP_ID"),
    AZURE_CONNECTION_STRING("AZURE_CONNECTION_STRING"),
    AZURE_RESOURCE_PROVIDER_REGISTRATIONS("AZURE_RESOURCE_PROVIDER_REGISTRATIONS"),
    AZURE_AKS_NODE_POOL("AZURE_AKS_NODE_POOL"),
    /**
     * Whether the Azure stacks otherwise deploying their own KeyCloak use instead the one at
     * {@code OIDC_DOMAIN_NAME} that the identity stack of the cloud deploys.  Optional: {@code false} when unset.
     */
    AZURE_KEYCLOAK_EXTERNAL("AZURE_KEYCLOAK_EXTERNAL"),
    AZURE_POLARIS_LOG_BUCKET("AZURE_POLARIS_LOG_BUCKET"),

    // OIDC provider (client-facing; any IdentityProvider implementation)
    OIDC_DOMAIN_NAME("OIDC_DOMAIN_NAME"),
    OIDC_PROTOCOL("OIDC_PROTOCOL"),
    OIDC_ISSUER_URL("OIDC_ISSUER_URL"),
    OIDC_EMAIL_DOMAIN("OIDC_EMAIL_DOMAIN"),

    // Keycloak (self-hosted OIDC provider deployment, via KeyCloakConfig)
    KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME("KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME"),
    KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD("KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD"),
    KEYCLOAK_DB("KEYCLOAK_DB"),
    KEYCLOAK_DB_URL("KEYCLOAK_DB_URL"),
    KEYCLOAK_DB_USERNAME("KEYCLOAK_DB_USERNAME"),
    KEYCLOAK_DB_PASSWORD("KEYCLOAK_DB_PASSWORD"),

    // Guacamole
    GUACAMOLE_DB_HOST("GUACAMOLE_DB_HOST"),
    GUACAMOLE_DB_USERNAME("GUACAMOLE_DB_USERNAME"),
    GUACAMOLE_DB_PASSWORD("GUACAMOLE_DB_PASSWORD"),

    // Gitea (via GiteaConfig), whose metadata is kept in the Postgres database gitea_<cluster> at GITEA_DB_HOST
    GITEA_DB_HOST("GITEA_DB_HOST"),
    GITEA_DB_USERNAME("GITEA_DB_USERNAME"),
    GITEA_DB_PASSWORD("GITEA_DB_PASSWORD"),
    /** One of disable, require, verify-ca or verify-full.  Optional: {@code require} when unset. */
    GITEA_DB_SSL_MODE("GITEA_DB_SSL_MODE"),
    /** Size of the volume holding Gitea's git repositories, eg {@code 20Gi}.  Optional: {@code 10Gi} when unset. */
    GITEA_STORAGE_SIZE("GITEA_STORAGE_SIZE"),
    GITEA_ADMIN_USERNAME("GITEA_ADMIN_USERNAME"),
    GITEA_ADMIN_PASSWORD("GITEA_ADMIN_PASSWORD"),

    // User that clients authenticate as, from users.csv
    USER_ID("USER_ID"),

    // Polaris
    POLARIS_ID("POLARIS_ID"),
    POLARIS_SECRET("POLARIS_SECRET"),
    POLARIS_ENDPOINT("POLARIS_ENDPOINT"),
    /**
     * How the setup steps reach the REST API of Polaris: "port-forward" (the default) through a tunnel to its pod, which needs no
     * ingress access, or "https" through its public domain name, which its ingress may restrict.
     */
    POLARIS_ACCESS("POLARIS_ACCESS"),
    POLARIS_DB_PASSWORD("POLARIS_DB_PASSWORD"),
    POLARIS_DB_HOST("POLARIS_DB_HOST"),
    POLARIS_NAMESPACE("POLARIS_NAMESPACE"),
    POLARIS_NAMESPACE_EXTERNAL("POLARIS_NAMESPACE_EXTERNAL"),
    POLARIS_TABLE_NAME("POLARIS_TABLE_NAME"),

    OAUTH2_CLIENT_ID("OAUTH2_CLIENT_ID"),
    OAUTH2_CLIENT_SECRET("OAUTH2_CLIENT_SECRET"),
    OAUTH2_COOKIE_SECRET("OAUTH2_COOKIE_SECRET"),

    // JUPYTER
    JUPYTER_VNC_PASSWORD("JUPYTER_VNC_PASSWORD"),

    // Snowflake
    SNOWFLAKE_JDBC_URL("SNOWFLAKE_JDBC_URL"),
    SNOWFLAKE_PRIVATE_KEY("SNOWFLAKE_PRIVATE_KEY"),

    // Spark Connect
    SPARK_CONNECT_URL("SPARK_CONNECT_URL"),

    ALLOWED_DOMAINS_CSV("ALLOWED_DOMAINS_CSV"),
    /** The cloud of the stack hosting the TRE, one of aws|azure|gcp.  Defaults to aws. */
    TRE_CLOUD("TRE_CLOUD"),
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
