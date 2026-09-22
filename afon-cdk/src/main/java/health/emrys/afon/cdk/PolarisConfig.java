package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.deployment.*;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.polaris.keycloak.KeyCloakHelper;
import health.emrys.afon.security.keycloak.KeyCloakConfigurer;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static health.emrys.afon.cdk.CdkHelper.clusterIpService;
import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.polaris.PolarisConstants.POLARIS_IMAGE_NAME;
import static health.emrys.afon.polaris.PolarisConstants.getPolarisProperties;
import static java.lang.String.format;

public class PolarisConfig extends Construct {

    private static final String          SECRET_NAME      = "my-app-secret";
    private static final String          POLARIS_ENV_FILE = "polaris_env.sh";
    private static final String          GCP_CRED_FILE    = "service-account.json";
    private static final String          SECRETS_DIR      = "/etc/app-secrets";
    public static final  ServiceSpecPort POLARIS_PORTS    = ServiceSpecPort.builder()
                                                                           .name("web")
                                                                           .port(8181)
                                                                           .targetPort("8181")
                                                                           .protocol("TCP")
                                                                           .build();

    /**
     * HDRS (Patch 20): Polaris serves the API on 8181 but its Quarkus health endpoints
     * on the separate management port 8182, which the Service did not expose - so the
     * ALB had no way to ask Polaris whether it was actually well, and its health check
     * fell back to GET / on 8181 (a 404). Exposing 8182 lets the target group use
     * /q/health/ready, which includes the datasource check - the signal that was DOWN
     * throughout the three-day Polaris outage and that nothing was watching.
     */
    public static final  ServiceSpecPort POLARIS_MGMT_PORT = ServiceSpecPort.builder()
                                                                           .name("management")
                                                                           .port(8182)
                                                                           .targetPort("8182")
                                                                           .protocol("TCP")
                                                                           .build();
    public static final  String          POLARIS_INTERNAL = "polaris-internal";

    public Map<String, String> polarisLabels;

    public PolarisConfig(@NonNull Construct scope, @NonNull String id, CloudProvider cloudProvider, AfonPropertyProvider properties) {
        super(scope, id);
        // Configure the Kubernetes Provider
        cloudProvider.configureK8s();

        // Define a Kubernetes Secret
        // The data must be Base64 encoded. Each key in the secret will become a file in the volume mount.
        Secret.Builder
            .create(this, "mySecret")
            .dependsOn(cloudProvider.getDependables())
            .metadata(SecretMetadata.builder().name(SECRET_NAME).build())
            .data(Map.of(
                POLARIS_ENV_FILE, getProviderCredentials(properties, cloudProvider.getId()),
                // HDRS (Patch 8): default to empty JSON. This shared class mounts a
                // GCP credentials file whatever the target cloud, so an AWS-only
                // config (Patch 4) would otherwise fail synth outright. The file is
                // written but never read - only the AWS catalog is created.
                GCP_CRED_FILE, properties.getValue(GOOGLE_APPLICATION_CREDENTIALS, "{}")))
            .type("Opaque")
            .build();
    }

    static String getProviderCredentials(
        AfonPropertyProvider properties,
        String deploymentId) {
        List<String> exports = getPolarisProperties()
            .stream()
            .map(x -> format("export %s=%s", x.getName(), shellQuote(properties.getValue(x))))
            .sorted()
            .toList();

        String kcConfig = kcExports(properties);

        String pgConfig = format(
            """
            export POLARIS_PERSISTENCE_TYPE="relational-jdbc"
            export QUARKUS_DATASOURCE_JDBC_URL="jdbc:postgresql://%s:5432/polaris_db_%s"
            export QUARKUS_DATASOURCE_USERNAME=%s
            export QUARKUS_DATASOURCE_PASSWORD=%s

            """,
            properties.getValue(POLARIS_DB_HOST),
            deploymentId,
            // HDRS (Patch 12): defaults to upstream's hardcoded "postgres", so
            // existing deployments are unaffected.
            shellQuote(properties.getValue(POLARIS_DB_USERNAME, "postgres")),
            shellQuote(properties.getValue(POLARIS_DB_PASSWORD)));

        return pgConfig + format(
            """
            %s

            %s
            export %s='%s'

            export QUARKUS_LOG_CATEGORY__ORG_APACHE_POLARIS_SERVICE_CATALOG_API__LEVEL=DEBUG
            export QUARKUS_LOG_CATEGORY__ORG_APACHE_POLARIS_SERVICE__LEVEL=DEBUG
            export QUARKUS_LOG_CATEGORY__ORG_APACHE_POLARIS_SERVICE_EXCEPTION__LEVEL=ERROR
            export QUARKUS_LOG_CATEGORY__IO_QUARKUS_HTTP_ACCESS-LOG__LEVEL=ERROR

            export POLARIS_FEATURES_ENABLE_CATALOG_FEDERATION=true
            export POLARIS_FEATURES_SUPPORTED_CATALOG_CONNECTION_TYPES="ICEBERG_REST,HIVE"
            export JAVA_OPTS_APPEND=-D"polaris.features.\\"ENABLE_CATALOG_FEDERATION\\"=true"
            """,
            kcConfig,
            String.join("\n", exports),
            GOOGLE_APPLICATION_CREDENTIALS.getName(),
            SECRETS_DIR + "/" + GCP_CRED_FILE
        );
    }

    private static @NonNull String kcExports(AfonPropertyProvider properties) {
        String kcHost = properties.getValue(KC_HOSTNAME);
        Map<String, String> kcProperties = KeyCloakHelper.polarisOidcEnv(
            KeyCloakConfigurer.internalRealmUrl(kcHost, "https"),
            properties.getValue(KC_DB_USERNAME),
            properties.getValue(KC_DB_PASSWORD));
        return toExports(kcProperties);
    }

    static @NonNull String toExports(Map<String, String> properties) {
        return properties
            .entrySet().stream().map(x ->
                                         format("export %s=%s", toEnvironmentVariable(x.getKey()), shellQuote(x.getValue()))
            ).collect(Collectors.joining("\n"));
    }

    /**
     * HDRS (Patch 11): single-quote a value for safe inclusion in the generated
     * {@code polaris_env.sh}, which the container SOURCES. Unquoted values break
     * the shell as soon as a secret contains a metacharacter - an RDS master
     * password auto-generated by Secrets Manager routinely does, and Polaris then
     * CrashLoopBackOffs with
     * {@code polaris_env.sh: line 4: syntax error near unexpected token `)'}.
     * Embedded single quotes are closed, escaped and reopened ('\\'' ).
     */
    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    public static String toEnvironmentVariable(String propertyName) {
        return propertyName
            .replace('.', '_')
            .replace('-', '_')
            .toUpperCase(Locale.ROOT);
    }

    public Service deployPolaris(List<ITerraformDependable> eksDependOn) {
        String secretVolume = "secret-volume";
        List<DeploymentSpecTemplateSpecVolume> volumes = List.of(
            DeploymentSpecTemplateSpecVolume
                .builder()
                .name(secretVolume)
                .secret(DeploymentSpecTemplateSpecVolumeSecret
                            .builder()
                            .secretName(SECRET_NAME)
                            .build())
                .build());


        List<DeploymentSpecTemplateSpecContainerVolumeMount> mounts =
            List.of(DeploymentSpecTemplateSpecContainerVolumeMount
                        .builder()
                        .name(secretVolume)
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
            .image(POLARIS_IMAGE_NAME)
            .command(commands)
            .volumeMount(mounts)
            .readinessProbe(readinessProbe)
            .build();
        polarisLabels = Map.of("app", "polaris");
        var polarisDeployment = CdkUtils.deploy(
            this,
            eksDependOn,
            polarisLabels,
            List.of(polarisContainer),
            volumes,
            "polaris");

        return configurePolarisService(polarisDeployment, polarisLabels);
    }

    protected Service configurePolarisService(Deployment polarisDeployment, Map<String, String> polarisLabels) {
        return clusterIpService(
            List.of(polarisDeployment),
            polarisLabels,
            POLARIS_INTERNAL,
            List.of(PolarisConfig.POLARIS_PORTS),
            this);
    }

}
