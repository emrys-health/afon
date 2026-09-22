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
    // HDRS (Patch 1): the cluster and network are pre-existing (built by
    // hdrsuk/infra-aws-poc) and looked up, not created - these identify them.
    AWS_VPC_ID("AWS_VPC_ID"),
    AWS_PRIVATE_SUBNET_IDS("AWS_PRIVATE_SUBNET_IDS"),

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
    // HDRS (Patch 12): the datasource user was hardcoded to "postgres", which is
    // not the master user on an RDS instance unless you happen to name it that.
    POLARIS_DB_USERNAME("POLARIS_DB_USERNAME"),

    // HDRS (Patch 13): remote state. Upstream configures CDKTF's LOCAL backend
    // in AbstractCloudStack, which puts the state in one un-backed-up file on
    // the operator's machine - no locking, and losing it means teardown by hand.
    TF_STATE_BUCKET("TF_STATE_BUCKET"),
    TF_STATE_KEY("TF_STATE_KEY"),
    TF_STATE_KMS_KEY("TF_STATE_KMS_KEY"),

    OIDC_ISSUER_URL("OIDC_ISSUER_URL"),
    OIDC_EMAIL_DOMAIN("OIDC_EMAIL_DOMAIN"),

    // HDRS Patches 14-16: the audit platform's perimeter sidecar in the workspace
    // pod (hdrsuk/svc-audit-log, docs/afon-integration.md). All optional, and
    // AUDIT_SIDECAR_IMAGE is the gate: unset, afon deploys exactly as before, with
    // no external auditor - which it must always be able to do.
    AUDIT_SIDECAR_IMAGE("AUDIT_SIDECAR_IMAGE"),
    AUDIT_KAFKA_BOOTSTRAP("AUDIT_KAFKA_BOOTSTRAP"),
    AUDIT_SCHEMA_REGISTRY_URL("AUDIT_SCHEMA_REGISTRY_URL"),
    AUDIT_AE_ID("AUDIT_AE_ID"),
    AUDIT_PROJECT_ID("AUDIT_PROJECT_ID"),
    AUDIT_TRE_ID("AUDIT_TRE_ID"),
    // The gate BETWEEN steps 2 and 3: unset leaves Patch 14's HTTP_PROXY behaviour.
    AUDIT_REDIRECT_IMAGE("AUDIT_REDIRECT_IMAGE"),
    AUDIT_TRANSPARENT_PORT("AUDIT_TRANSPARENT_PORT"),
    AUDIT_EXCLUDED_PORTS("AUDIT_EXCLUDED_PORTS"),

    // HDRS Patch 25: the port the perimeter sidecar listens on for notebook cells,
    // posted to it by the Jupyter Server extension in the workspace
    // (hdrsuk/svc-audit-log, ADR 0031). Loopback only - the sidecar and the notebook
    // share the pod's network namespace, so nothing outside the pod can reach it.
    //
    // Defaults to 15003. Loopback inside the pod, so it collides with nothing outside
    // it, and the transparent redirect returns loopback traffic untouched
    // (services/perimeter-redirect/redirect.sh) so a cell never crosses the proxy.
    //
    // Not a gate. AUDIT_SIDECAR_IMAGE is: the sensor is copied into the workspace out of
    // the sidecar's own image, so a deployment with no sidecar has no intake, no init
    // container and no extension either.
    AUDIT_CELL_INTAKE_PORT("AUDIT_CELL_INTAKE_PORT"),

    // HDRS Patch 20: Tetragon, the eBPF exec sensor whose JSON export the audit
    // platform's node agent consumes (hdrsuk/svc-audit-log, ADR 0031). It lives
    // here rather than in infra-aws-poc because afon is the thing a TRE is given
    // to run, so what the platform needs *from* a TRE belongs in afon rather
    // than in HDRS's own cluster repository.
    //
    // On/off only. What Tetragon's export is SCOPED to is not configurable and
    // must not become so: it is afon's own singleuser pod label, which
    // JupyterHubConfig already defines and stamps, so the selector is derived
    // from that constant rather than retyped in a properties file nothing
    // checks (Patch 24).
    //
    // Defaults to TRUE, unlike every other AUDIT_* key. This is the HDRS fork:
    // a TRE adopting it is adopting the audit platform with it, so the exec
    // sensor is part of what afon is here rather than a feature to remember to
    // switch on. Set to `false` to deploy without it - the escape hatch for a
    // cluster owner who will not accept a privileged cluster-wide DaemonSet.
    AUDIT_EXEC_SENSOR_ENABLED("AUDIT_EXEC_SENSOR_ENABLED"),
    // Where the export is written on the node, and therefore what the node agent
    // has to mount. Defaulted rather than required: the two sides must agree and
    // only one of them is configured here.
    AUDIT_EXEC_SENSOR_EXPORT_DIR("AUDIT_EXEC_SENSOR_EXPORT_DIR"),

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
     * Use for optional properties that have a sensible default (e.g. the
     * KeyCloak scheme, which defaults to https for real deployments but is overridden to http in tests).
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
