package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.deployment.*;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1Metadata;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1Spec;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1Timeouts;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1SpecTemplate;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1SpecTemplateMetadata;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1SpecTemplateSpec;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1SpecTemplateSpecContainer;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1SpecTemplateSpecContainerEnv;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1SpecTemplateSpecContainerVolumeMount;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1SpecTemplateSpecInitContainer;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1SpecTemplateSpecInitContainerEnv;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1SpecTemplateSpecInitContainerVolumeMount;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1SpecTemplateSpecVolume;
import com.hashicorp.cdktf.providers.kubernetes.job_v1.JobV1SpecTemplateSpecVolumeEmptyDir;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.polaris.keycloak.KeyCloakHelper;
import health.emrys.afon.security.keycloak.KeyCloakConfigurer;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static health.emrys.afon.cdk.CdkHelper.clusterIpService;
import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.polaris.PolarisConstants.*;
import static java.lang.String.format;

public class PolarisConfig extends Construct {

    // TODO make DB_PORT configurable via properties?
    private static final int             DB_PORT          = 5432;
    private static final String          DB_INIT_JOB      = "polaris-db-init";
    private static final String          REALM            = "POLARIS";
    private static final String          SECRETS_DIR      = "/etc/app-secrets";
    private static final String          SECRET_NAME      = "polaris-secret";
    private static final String          SECRET_VOLUME    = "secret-volume";
    private static final String          POLARIS_ENV_FILE = "polaris_env.sh";
    private static final String      GCP_CREDENTIALS_FILE = "service-account.json";

    private final JobV1 polarisDbInitJob;
    private final ServiceSpecPort polarisSpecPort;
    private final Map<String, String> polarisLabels;

    public PolarisConfig(@NonNull Construct scope, @NonNull String id, CloudProvider cloudProvider, AfonPropertyProvider properties) {
        super(scope, id);
        // Configure the Kubernetes Provider
        cloudProvider.configureK8s();

        // Define a Kubernetes Secret
        // The data must be Base64 encoded. Each key in the secret will become a file in the volume mount.
        Secret.Builder
            .create(this, "polarisSecret")
            .dependsOn(cloudProvider.getDependables())
            .metadata(SecretMetadata.builder().name(SECRET_NAME).build())
            .data(Map.of(
                POLARIS_ENV_FILE, getProviderCredentials(properties, cloudProvider.getId()),
                    GCP_CREDENTIALS_FILE, properties.getValue(GOOGLE_APPLICATION_CREDENTIALS)))
            .type("Opaque")
            .build();

        // Shared db instance needs a per-domain database plus the Polaris schema and
        // root principal in it.
        // polaris-db-init job creates/bootstraps these if absent before Polaris starts.
        polarisDbInitJob = buildDbInitJob(
            this,
            new ArrayList<>(cloudProvider.getDependables()),
            properties.getValue(POLARIS_DB_HOST),
            properties.getValue(POLARIS_DB_PASSWORD),
            polarisDbName(properties, cloudProvider.getId()),
            properties.getValue(POLARIS_ID),
            properties.getValue(POLARIS_SECRET));

        polarisSpecPort = ServiceSpecPort.builder()
                .name("web")
                .port(8181)
                .targetPort("8181")
                .protocol("TCP")
                .build();

        polarisLabels = Map.of("app", "polaris");
    }

    public ServiceSpecPort getPolarisSpecPort() { return polarisSpecPort; }

    public Map<String, String> getPolarisLabels() { return polarisLabels; }

    /**
     * One-shot Job that prepares the Polaris database on the shared Postgres instance:
     * <ol>
     *   <li>an initContainer creates the database (if absent) and checks whether the Polaris schema
     *       is already there (a fresh {@code CREATE DATABASE} has none);</li>
     *   <li>the main container runs {@code apache/polaris-admin-tool}'s {@code bootstrap} command —
     *       the same tool Polaris itself ships for this — to create the schema and the root
     *       principal ({@code POLARIS_ID}/{@code POLARIS_SECRET}) for the {@value REALM} realm,
     *       skipping if the initContainer found it already done.
     * </ol>
     * The skip check matters because {@code bootstrap} is not idempotent: rerunning it against an
     * already-bootstrapped realm fails (it demands a {@code purge} first), which would otherwise
     * break every redeploy after the first. Unlike {@link GuacamoleConfig}'s db-init Job, the schema
     * SQL itself isn't hand-rolled here — it comes from Polaris's own admin tool, so it stays in
     * lock-step with whatever schema version the deployed Polaris image actually expects.
     */
    private static JobV1 buildDbInitJob(Construct scope,
                                        List<ITerraformDependable> dependsOn,
                                        String host,
                                        String password,
                                        String dbName,
                                        String polarisId,
                                        String polarisSecret) {
        String stateDir     = "/state";
        String bootstrapFlag = stateDir + "/already-bootstrapped";

        String createDbScript = String.join("\n",
            "set -e",
            "until pg_isready -h \"$PGHOST\" -p \"$PGPORT\" -U \"$PGUSER\"; do echo 'waiting for postgres'; sleep 2; done",
            "psql -d postgres -tAc \"SELECT 1 FROM pg_database WHERE datname='$POLARIS_DB'\" | grep -q 1 \\",
            "  || psql -d postgres -c \"CREATE DATABASE \\\"$POLARIS_DB\\\"\"",
            // Polaris's relational-jdbc schema lives in its own "polaris_schema" schema, not "public".
            "if [ \"$(psql -d \"$POLARIS_DB\" -tAc \"SELECT to_regclass('polaris_schema.entities')\")\" = 'polaris_schema.entities' ]; then",
            "  touch " + bootstrapFlag,
            "fi",
            "echo 'polaris db ready'");

        JobV1SpecTemplateSpecInitContainer dbCreate = JobV1SpecTemplateSpecInitContainer
                .builder()
                .name("db-create")
                .image(DockerImages.POSTGRES_ALPINE_IMAGE)
                .command(List.of("sh", "-c", createDbScript))
                .env(List.of(
                        initJobEnv("PGHOST", host),
                        initJobEnv("PGPORT", String.valueOf(DB_PORT)),
                        initJobEnv("PGUSER", "postgres"),
                        initJobEnv("PGPASSWORD", password),
                        initJobEnv("POLARIS_DB", dbName)))
                .volumeMount(List.of(JobV1SpecTemplateSpecInitContainerVolumeMount.builder()
                                                                                   .name("state")
                                                                                   .mountPath(stateDir)
                                                                                   .build()))
                .build();

        // apache/polaris-admin-tool's entrypoint runs the bootstrap tool directly (no shell), so
        // "sh -c" is used here to make the skip conditional on the initContainer's flag file.
        String bootstrapScript = String.join("\n",
            "set -e",
            "if [ -f " + bootstrapFlag + " ]; then",
            "  echo 'Polaris realm " + REALM + " already bootstrapped, skipping'",
            "  exit 0",
            "fi",
            "java -jar /deployments/polaris-admin-tool.jar bootstrap -r " + REALM
                + " -c \"" + REALM + ",$POLARIS_ID,$POLARIS_SECRET\"");

        JobV1SpecTemplateSpecContainer bootstrap = JobV1SpecTemplateSpecContainer
                .builder()
                .name("bootstrap")
                .image(DockerImages.POLARIS_ADMIN_TOOL_IMAGE)
                .command(List.of("sh", "-c", bootstrapScript))
                .env(List.of(
                        jobEnv("POLARIS_PERSISTENCE_TYPE", "relational-jdbc"),
                        jobEnv("QUARKUS_DATASOURCE_JDBC_URL",
                                format("jdbc:postgresql://%s:%d/%s", host, DB_PORT, dbName)),
                        jobEnv("QUARKUS_DATASOURCE_USERNAME", "postgres"),
                        jobEnv("QUARKUS_DATASOURCE_PASSWORD", password),
                        jobEnv("POLARIS_ID", polarisId),
                        jobEnv("POLARIS_SECRET", polarisSecret)))
                .volumeMount(List.of(JobV1SpecTemplateSpecContainerVolumeMount.builder()
                                                                               .name("state")
                                                                               .mountPath(stateDir)
                                                                               .readOnly(true)
                                                                               .build()))
                .build();

        return JobV1.Builder.create(scope, "polarisDbInitJob")
                .dependsOn(dependsOn)
                // The provider waits only 1 minute for completion by default, most of which is spent
                // pulling the images before any work runs. The work itself takes a few seconds.
                .timeouts(JobV1Timeouts.builder().create("10m").build())
                .metadata(JobV1Metadata.builder().name(DB_INIT_JOB).namespace("default").build())
                .spec(JobV1Spec.builder()
                              .backoffLimit(6)
                              .template(JobV1SpecTemplate.builder()
                                    .metadata(JobV1SpecTemplateMetadata.builder()
                                                      .labels(Map.of("app", DB_INIT_JOB))
                                                      .build())
                                    .spec(JobV1SpecTemplateSpec.builder()
                                                  .restartPolicy("OnFailure")
                                                  .initContainer(List.of(dbCreate))
                                                  .container(List.of(bootstrap))
                                                  .volume(List.of(JobV1SpecTemplateSpecVolume.builder()
                                                          .name("state")
                                                          .emptyDir(JobV1SpecTemplateSpecVolumeEmptyDir.builder().build())
                                                          .build()))
                                                  .build())
                                    .build())
                              .build())
                .build();
    }

    private static JobV1SpecTemplateSpecContainerEnv jobEnv(String name, String value) {
        return JobV1SpecTemplateSpecContainerEnv.builder().name(name).value(value).build();
    }

    private static JobV1SpecTemplateSpecInitContainerEnv initJobEnv(String name, String value) {
        return JobV1SpecTemplateSpecInitContainerEnv.builder().name(name).value(value).build();
    }

    static String getProviderCredentials(
        AfonPropertyProvider properties,
        String deploymentId) {
        List<String> exports = getPolarisProperties()
            .entrySet()
            .stream()
            .map(e -> format("export %s=%s", e.getValue(), properties.getValue(e.getKey())))
            .sorted()
            .toList();

        String kcConfig = kcExports(properties);

        String pgConfig = format(
            """
            export POLARIS_PERSISTENCE_TYPE="relational-jdbc"
            export QUARKUS_DATASOURCE_JDBC_URL="jdbc:postgresql://%s:%d/%s"
            export QUARKUS_DATASOURCE_USERNAME=postgres
            export QUARKUS_DATASOURCE_PASSWORD=%s

            """,
            properties.getValue(POLARIS_DB_HOST),
            DB_PORT,
            polarisDbName(properties, deploymentId),
            properties.getValue(POLARIS_DB_PASSWORD));

        return pgConfig + format(
            """
            %s

            %s
            export %s='%s'

            export QUARKUS_LOG_CATEGORY__ORG_APACHE_POLARIS_SERVICE_CATALOG_API__LEVEL=DEBUG
            export QUARKUS_LOG_CATEGORY__ORG_APACHE_POLARIS_SERVICE__LEVEL=DEBUG
            export QUARKUS_LOG_CATEGORY__ORG_APACHE_POLARIS_SERVICE_EXCEPTION__LEVEL=ERROR
            export QUARKUS_LOG_CATEGORY__IO_QUARKUS_HTTP_ACCESS_LOG__LEVEL=ERROR

            export POLARIS_FEATURES_ENABLE_CATALOG_FEDERATION=true
            export POLARIS_FEATURES_SUPPORTED_CATALOG_CONNECTION_TYPES="ICEBERG_REST,HIVE"
            export JAVA_OPTS_APPEND=-D"polaris.features.\\"ENABLE_CATALOG_FEDERATION\\"=true"
            """,
            kcConfig,
            String.join("\n", exports),
            GOOGLE_APPLICATION_CREDENTIALS.getName(),
            SECRETS_DIR + "/" + GCP_CREDENTIALS_FILE
        );
    }

    /**
     * Returns the Polaris db name created using the Polaris domain name (with '.' -> '_').
     *
     * @param properties
     * @param deploymentId
     * @return The Polaris db name "polaris_db_<domain_name_with_no_dots>"
     */
    private static String polarisDbName(AfonPropertyProvider properties, String deploymentId) {
        return String.format("polaris_db_%s", polarisDomainName(properties, deploymentId).replace(".", "_"));
    }

    static String polarisDomainName(AfonPropertyProvider properties, String deploymentId) {
        switch (AfonCloud.valueOf(deploymentId.toUpperCase())) {
            case AWS:
                return properties.getValue(AWS_POLARIS_DOMAIN_NAME);
            case AZURE:
                return properties.getValue(AZURE_POLARIS_DOMAIN_NAME);
            case GCP:
                return properties.getValue(GCP_POLARIS_DOMAIN_NAME);
            default:
                throw new IllegalArgumentException(deploymentId);
        }
    }

    private static @NonNull String kcExports(AfonPropertyProvider properties) {
        String kcHost = properties.getValue(OIDC_DOMAIN_NAME);
        Map<String, String> kcProperties = KeyCloakHelper.polarisOidcEnv(
            KeyCloakConfigurer.internalRealmUrl(kcHost, "https"),
            properties.getValue(KEYCLOAK_DB_USERNAME),
            properties.getValue(KEYCLOAK_DB_PASSWORD));
        return toExports(kcProperties);
    }

    static @NonNull String toExports(Map<String, String> properties) {
        return properties
                .entrySet().stream().map(x ->
                        format("export %s=%s", toEnvironmentVariable(x.getKey()), x.getValue())
                ).collect(Collectors.joining("\n"));
    }

    private static String toEnvironmentVariable(String propertyName) {
        return propertyName
            .replace('.', '_')
            .replace('-', '_')
            .toUpperCase(Locale.ROOT);
    }

    public Service deployPolaris(List<ITerraformDependable> eksDependOn) {
        List<ITerraformDependable> dependsOn = new ArrayList<>(eksDependOn);
        dependsOn.add(polarisDbInitJob);

        List<DeploymentSpecTemplateSpecVolume> volumes = List.of(
            DeploymentSpecTemplateSpecVolume
                .builder()
                .name(SECRET_VOLUME)
                .secret(DeploymentSpecTemplateSpecVolumeSecret
                            .builder()
                            .secretName(SECRET_NAME)
                            .build())
                .build());


        List<DeploymentSpecTemplateSpecContainerVolumeMount> mounts =
            List.of(DeploymentSpecTemplateSpecContainerVolumeMount
                        .builder()
                        .name(SECRET_VOLUME)
                        .mountPath(SECRETS_DIR)
                        .readOnly(true)
                        .build());

        List<String> commands = List.of(
            "/bin/bash",
            "-c",
            format(
                "source %s/%s && /opt/jboss/container/java/run/run-java.sh",
                SECRETS_DIR, POLARIS_ENV_FILE));
        DeploymentSpecTemplateSpecContainerReadinessProbe readinessProbe =
            DeploymentSpecTemplateSpecContainerReadinessProbe
                .builder()
                .httpGet(DeploymentSpecTemplateSpecContainerReadinessProbeHttpGet
                             .builder()
                             .path("/q/health")
                             .port("8182")
                             .build())
                .initialDelaySeconds(30)
                .periodSeconds(10)
                .failureThreshold(6)
                .build();

        DeploymentSpecTemplateSpecContainer polarisContainer = DeploymentSpecTemplateSpecContainer
            .builder()
            .name("polaris")
            .image(DockerImages.POLARIS_IMAGE)
            .command(commands)
            .volumeMount(mounts)
            .readinessProbe(readinessProbe)
            .build();
        var polarisDeployment = CdkUtils.deploy(
            this,
            dependsOn,
            polarisLabels,
            List.of(polarisContainer),
            volumes,
            "polaris");

        return configurePolarisService(polarisDeployment);
    }

    protected Service configurePolarisService(Deployment polarisDeployment) {
        return clusterIpService(
            List.of(polarisDeployment),
            polarisLabels,
                POLARIS_INTERNAL_SERVICE_NAME,
            List.of(polarisSpecPort),
            this);
    }

}
