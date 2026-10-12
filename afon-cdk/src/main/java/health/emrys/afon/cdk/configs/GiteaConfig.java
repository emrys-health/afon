package health.emrys.afon.cdk.configs;

import health.emrys.afon.cdk.CdkUtils;
import health.emrys.afon.cdk.DockerImages;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.config.AfonProperty.*;

/**
 * Deploys Gitea via its official Helm chart, keeping its metadata (users, repositories, issues, pull requests...) in
 * a Postgres database and its blobs in the object storage of the configured {@link AfonCloud}: S3 on AWS, GCS on GCP
 * and Blob Storage on Azure.
 *
 * <p>Gitea serves git itself from a filesystem, so the bare repositories stay on the chart's persistent volume; what
 * Gitea can put in object storage - LFS objects, attachments, packages, avatars, repository archives and Actions
 * logs and artifacts - goes under {@code gitea/<cluster>/} of the cloud's bucket, so that the stacks sharing a bucket
 * keep theirs apart.
 *
 * <p>The database {@code gitea_<cluster>} is created, if absent, by an init container of the Gitea pod; Gitea then
 * creates and migrates its own schema.  The chart's bundled Postgres and Valkey are disabled: being a single replica,
 * Gitea keeps its sessions and cache in memory and its queues on its volume.
 *
 * <p>Requires properties:
 * <ul>
 *     <li>{@code GITEA_DB_HOST}, {@code GITEA_DB_USERNAME}, {@code GITEA_DB_PASSWORD}</li>
 *     <li>optionally {@code GITEA_DB_SSL_MODE}, defaulting to {@code require}</li>
 *     <li>{@code GITEA_ADMIN_USERNAME}, {@code GITEA_ADMIN_PASSWORD}</li>
 *     <li>{@code CLUSTER_NAME}</li>
 *     <li>AWS: {@code AWS_GITEA_BUCKET}, {@code AWS_REGION}, {@code AWS_ACCESS_KEY_ID}, {@code AWS_SECRET_ACCESS_KEY}</li>
 *     <li>GCP: {@code GCP_GITEA_BUCKET}, {@code GCP_REGION}, {@code GCP_GITEA_HMAC_ACCESS_ID},
 *     {@code GCP_GITEA_HMAC_SECRET}</li>
 *     <li>Azure: {@code AZURE_GITEA_BUCKET} (a container), {@code AZURE_CONNECTION_STRING}</li>
 * </ul>
 */
public class GiteaConfig {

    static final String SECRET_NAME = "gitea-secrets";
    private static final String CHART_FILE  = "gitea-12.7.0.tgz";
    private static final int    DB_PORT     = 5432;

    /** Keys of {@link #SECRET_NAME}: the admin's, as the chart expects them, then app.ini sections, then Postgres'. */
    private static final String ADMIN_USERNAME_KEY   = "username";
    private static final String ADMIN_PASSWORD_KEY   = "password";
    private static final String DATABASE_SECTION_KEY = "database";
    private static final String STORAGE_SECTION_KEY  = "storage";
    private static final String DB_PASSWORD_KEY      = "db-password";

    private final AfonPropertyProvider properties;
    private final AfonCloud cloud;

    public GiteaConfig(@NonNull AfonPropertyProvider properties, @NonNull AfonCloud cloud) {
        this.properties = properties;
        this.cloud = cloud;
    }

    /**
     * Installs the Gitea chart against the cluster targeted by {@code helmProvider}.
     *
     * @param scope        the CDKTF stack the release is scoped to.
     * @param helmProvider the Helm provider wired to the target cluster (Azure aliases its provider).
     * @param dependsOn    must include the cluster's dependables (so the API server is reachable).
     */
    public Release deploy(@NonNull Construct scope,
                          @NonNull HelmProvider helmProvider,
                          @NonNull List<? extends ITerraformDependable> dependsOn) {
        Secret secret = Secret.Builder
            .create(scope, "giteaSecrets")
            .metadata(SecretMetadata.builder()
                                    .name(SECRET_NAME)
                                    .namespace("default")
                                    .build())
            .data(secretData())
            .dependsOn(dependsOn)
            .build();

        List<ITerraformDependable> releaseDependsOn = new ArrayList<>(dependsOn);
        releaseDependsOn.add(secret);

        return Release.Builder.create(scope, "gitea")
                              .provider(helmProvider)
                              .name("gitea")
                              .chart(CdkUtils.vendoredChart(scope, "giteaChart", CHART_FILE))
                              .namespace("default")
                              .atomic(true)
                              .timeout(600)
                              .values(List.of(values()))
                              .dependsOn(releaseDependsOn)
                              .build();
    }

    /** The Postgres database Gitea keeps its metadata in, one per cluster, as Postgres identifiers allow. */
    String databaseName() {
        return "gitea_" + properties.getValue(CLUSTER_NAME).replaceAll("[^A-Za-z0-9_]", "_");
    }

    /** One of disable, require, verify-ca or verify-full, which Gitea and psql both take. */
    private String sslMode() {
        String sslMode = properties.getValue(GITEA_DB_SSL_MODE, "");
        return sslMode.isBlank() ? "require" : sslMode;
    }

    /** Where, in the cloud's bucket, Gitea's blobs go. */
    String basePath() {
        return "gitea/" + properties.getValue(CLUSTER_NAME) + "/";
    }

    Map<String, String> secretData() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put(ADMIN_USERNAME_KEY, properties.getValue(GITEA_ADMIN_USERNAME));
        data.put(ADMIN_PASSWORD_KEY, properties.getValue(GITEA_ADMIN_PASSWORD));
        data.put(DATABASE_SECTION_KEY, iniSection(databaseSection()));
        data.put(STORAGE_SECTION_KEY, iniSection(storageSection()));
        data.put(DB_PASSWORD_KEY, properties.getValue(GITEA_DB_PASSWORD));
        return data;
    }

    Map<String, String> databaseSection() {
        Map<String, String> section = new LinkedHashMap<>();
        section.put("DB_TYPE", "postgres");
        section.put("HOST", properties.getValue(GITEA_DB_HOST) + ":" + DB_PORT);
        section.put("NAME", databaseName());
        section.put("USER", properties.getValue(GITEA_DB_USERNAME));
        section.put("PASSWD", properties.getValue(GITEA_DB_PASSWORD));
        section.put("SSL_MODE", sslMode());
        return section;
    }

    /**
     * Gitea's default {@code [storage]}, which every kind of blob it stores in object storage inherits.  S3 and GCS
     * are both reached through Gitea's S3 client ({@code minio}), GCS by its S3 compatible API.
     */
    Map<String, String> storageSection() {
        String bucket = properties.getCloudValue(cloud, "GITEA_BUCKET");
        Map<String, String> section = new LinkedHashMap<>();
        switch (cloud) {
            case AWS -> {
                String region = properties.getValue(AWS_REGION);
                section.put("STORAGE_TYPE", "minio");
                section.put("MINIO_ENDPOINT", "s3." + region + ".amazonaws.com");
                section.put("MINIO_LOCATION", region);
                section.put("MINIO_BUCKET", bucket);
                section.put("MINIO_BASE_PATH", basePath());
                section.put("MINIO_USE_SSL", "true");
                section.put("MINIO_ACCESS_KEY_ID", properties.getValue(AWS_ACCESS_KEY_ID));
                section.put("MINIO_SECRET_ACCESS_KEY", properties.getValue(AWS_SECRET_ACCESS_KEY));
            }
            case GCP -> { // GCP accepts S3 requests, apparently...
                section.put("STORAGE_TYPE", "minio");
                section.put("MINIO_ENDPOINT", "storage.googleapis.com");
                section.put("MINIO_LOCATION", properties.getValue(GCP_REGION));
                section.put("MINIO_BUCKET", bucket);
                section.put("MINIO_BASE_PATH", basePath());
                section.put("MINIO_USE_SSL", "true");
                section.put("MINIO_ACCESS_KEY_ID", properties.getValue(GCP_GITEA_HMAC_ACCESS_ID));
                section.put("MINIO_SECRET_ACCESS_KEY", properties.getValue(GCP_GITEA_HMAC_SECRET));
            }
            case AZURE -> {
                Map<String, String> connection = parseConnectionString(properties.getValue(AZURE_CONNECTION_STRING));
                String account = required(connection, "AccountName");
                String suffix = connection.getOrDefault("EndpointSuffix", "core.windows.net");
                section.put("STORAGE_TYPE", "azureblob");
                section.put("AZURE_BLOB_ENDPOINT", "https://" + account + ".blob." + suffix);
                section.put("AZURE_BLOB_ACCOUNT_NAME", account);
                section.put("AZURE_BLOB_ACCOUNT_KEY", required(connection, "AccountKey"));
                section.put("AZURE_BLOB_CONTAINER", bucket);
                section.put("AZURE_BLOB_BASE_PATH", basePath());
            }
        }
        return section;
    }

    /**
     * Values for the {@code gitea} chart.  The secret settings are not here, but in {@link #SECRET_NAME}, which the
     * chart merges into app.ini, picking only the app.ini sections out of it.
     */
    String values() {
        Map<String, Object> createDatabase = Map.of(
            "name", "create-database",
            "image", DockerImages.POSTGRES_ALPINE_IMAGE,
            "command", List.of("sh", "-c", """
                set -e
                until pg_isready -h "$PGHOST" -p "$PGPORT" -U "$PGUSER"; do echo 'waiting for postgres'; sleep 2; done
                psql -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$GITEA_DB'" | grep -q 1 \\
                  || psql -d postgres -c "CREATE DATABASE \\"$GITEA_DB\\""
                """),
            "env", List.of(
                Map.of("name", "PGHOST", "value", properties.getValue(GITEA_DB_HOST)),
                Map.of("name", "PGPORT", "value", String.valueOf(DB_PORT)),
                Map.of("name", "PGUSER", "value", properties.getValue(GITEA_DB_USERNAME)),
                Map.of("name", "PGPASSWORD", "valueFrom",
                       Map.of("secretKeyRef", Map.of("name", SECRET_NAME, "key", DB_PASSWORD_KEY))),
                Map.of("name", "PGSSLMODE", "value", sslMode()),
                Map.of("name", "GITEA_DB", "value", databaseName())));

        Map<String, Object> values = Map.of(
            // A single replica on a ReadWriteOnce volume: the old pod must let go of it before the new one starts.
            "strategy", Map.of("type", "Recreate"),
            "preExtraInitContainers", List.of(createDatabase),
            "gitea", Map.of(
                "admin", Map.of("existingSecret", SECRET_NAME),
                "config", Map.of("server", Map.of("LFS_START_SERVER", true)),
                "additionalConfigSources", List.of(Map.of("secret", Map.of(
                    "secretName", SECRET_NAME,
                    "items", List.of(Map.of("key", DATABASE_SECTION_KEY, "path", DATABASE_SECTION_KEY),
                                     Map.of("key", STORAGE_SECTION_KEY, "path", STORAGE_SECTION_KEY)))))),
            "postgresql", Map.of("enabled", false),
            "postgresql-ha", Map.of("enabled", false),
            "valkey", Map.of("enabled", false),
            "valkey-cluster", Map.of("enabled", false));

        // JSON is valid YAML; canonical so that Helm, diffing the values as text, sees no spurious change.
        return CdkUtils.toCanonicalJson(values);
    }

    /** An app.ini section as the chart's additional config sources take it: one {@code KEY=value} per line. */
    static String iniSection(Map<String, String> settings) {
        StringBuilder ini = new StringBuilder();
        settings.forEach((key, value) -> ini.append(key).append('=').append(value).append('\n'));
        return ini.toString();
    }

    /** Azure's {@code Key=Value;...} storage connection string; values, eg base64 keys, may themselves hold '='. */
    static Map<String, String> parseConnectionString(String connectionString) {
        Map<String, String> parts = new LinkedHashMap<>();
        for (String part : connectionString.split(";")) {
            String[] keyValue = part.split("=", 2);
            if (keyValue.length == 2) {
                parts.put(keyValue[0].trim(), keyValue[1].trim());
            }
        }
        return parts;
    }

    private static String required(Map<String, String> connection, String key) {
        String value = connection.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(AZURE_CONNECTION_STRING + " has no " + key);
        }
        return value;
    }
}
