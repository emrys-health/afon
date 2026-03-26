package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.deployment.*;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import health.emrys.afon.config.AfonPropertiesLoader;
import org.jetbrains.annotations.NotNull;
import software.constructs.Construct;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.infra.Defaults.POLARIS_SERVICE_NAME;
import static health.emrys.afon.polaris.PolarisConstants.POLARIS_IMAGE_NAME;
import static health.emrys.afon.polaris.PolarisConstants.POLARIS_PROPERTIES;
import static java.lang.String.format;

public class PolarisConfig extends Construct {

    private static final String              SECRET_NAME           = "my-app-secret";
    private static final String              POLARIS_ENV_FILE      = "polaris_env.sh";
    private static final String              GCP_CRED_FILE         = "service-account.json";
    private static final String              SECRETS_DIR           = "/etc/app-secrets";
    public static final  ServiceSpecPort     POLARIS_PORTS         = ServiceSpecPort.builder()
                                                                                    .name("web")
                                                                                    .port(8181)
                                                                                    .targetPort("8181")
                                                                                    .protocol("TCP")
                                                                                    .build();
    static final         String              NO_POLARIS_FINALIZERS = finalizeIngress("polaris-ingress");
    static final         String              NO_JUPYTER_FINALIZERS = finalizeIngress("jupyter-ingress");

    private static String finalizeIngress(String service) {
        return format("kubectl patch ingress %s -p '{\"metadata\":{\"finalizers\":[]}}' --type=merge", service);
    }

    protected            Map<String, String> polarisLabels;

    public PolarisConfig(@NotNull Construct scope, @NotNull String id, CloudProvider cloudProvider) {
        super(scope, id);
        // Configure the Kubernetes Provider
        cloudProvider.configureK8s();

        // Define a Kubernetes Secret
        // The data must be Base64 encoded. Each key in the secret will become a file in the volume mount.
        Properties properties = appProperties();
        Secret.Builder.create(this, "mySecret")
                      .dependsOn(cloudProvider.getDependables())
                      .metadata(SecretMetadata.builder().name(SECRET_NAME).build())
                      .data(Map.of(POLARIS_ENV_FILE, getProviderCredentials(properties, cloudProvider.getId()),
                                   GCP_CRED_FILE, GOOGLE_APPLICATION_CREDENTIALS.getValueFrom(properties)))
                      .type("Opaque")
                      .build();
    }

    private static Properties appProperties() {
        AfonPropertiesLoader loader     = new AfonPropertiesLoader();
        Properties           properties = loader.getProperties();
        return properties;
    }

    static String getProviderCredentials(Properties properties,
                                         String deploymentId) {
        List<String> exports = POLARIS_PROPERTIES
            .stream()
            .map(x ->  format("export %s=%s", x.getPropertyName(), x.getValueFrom(properties)))
            .sorted()
            .toList();

        String pgConfig = format("""
                              export POLARIS_PERSISTENCE_TYPE="relational-jdbc"
                              export QUARKUS_DATASOURCE_JDBC_URL="jdbc:postgresql://%s:5432/polaris_db_%s"
                              export QUARKUS_DATASOURCE_USERNAME=postgres
                              export QUARKUS_DATASOURCE_PASSWORD=%s
                              
                                     """,
                                 POLARIS_DB_HOST.getValueFrom(properties),
                                 deploymentId,
                                 POLARIS_DB_PASSWORD.getValueFrom(properties));

        return pgConfig + format("""
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
                      String.join("\n", exports),
                      GOOGLE_APPLICATION_CREDENTIALS.getPropertyName(), SECRETS_DIR + "/" + GCP_CRED_FILE
                     );
    }

    public Service deployPolaris(List<ITerraformDependable> eksDependOn) {
        String secretVolume = "secret-volume";
        List<DeploymentSpecTemplateSpecVolume> volumes = List.of(DeploymentSpecTemplateSpecVolume.builder()
                                                                                                 .name(secretVolume)
                                                                                                 .secret(DeploymentSpecTemplateSpecVolumeSecret.builder()
                                                                                                                                               .secretName(SECRET_NAME)
                                                                                                                                               .build())
                                                                                                 .build());


        List<DeploymentSpecTemplateSpecContainerVolumeMount> mounts = List.of(DeploymentSpecTemplateSpecContainerVolumeMount.builder()
                                                                                                                            .name(secretVolume)
                                                                                                                            .mountPath(SECRETS_DIR)
                                                                                                                            .readOnly(true)
                                                                                                                            .build());

        List<String> commands = List.of("/bin/bash",
                                        "-c",
                                        format("source %s/%s && /opt/jboss/container/java/run/run-java.sh",
                                               SECRETS_DIR, POLARIS_ENV_FILE));
        DeploymentSpecTemplateSpecContainerReadinessProbe readinessProbe =
                DeploymentSpecTemplateSpecContainerReadinessProbe.builder()
                        .httpGet(DeploymentSpecTemplateSpecContainerReadinessProbeHttpGet.builder()
                                .path("/q/health")
                                .port("8182")
                                .build())
                        .initialDelaySeconds(30)
                        .periodSeconds(10)
                        .failureThreshold(6)
                        .build();

        DeploymentSpecTemplateSpecContainer polarisContainer = DeploymentSpecTemplateSpecContainer.builder()
                                                                                                  .name("polaris")
                                                                                                  .image(POLARIS_IMAGE_NAME)
//                                                                                                  .env(envVars)
                                                                                                  .command(commands)
                                                                                                  .volumeMount(mounts)
                                                                                                  .readinessProbe(readinessProbe)
                                                                                                  .build();
        polarisLabels = Map.of("app", "polaris");
        var polarisDeployment = DeploymentUtils.deploy(
                this,
                eksDependOn,
                polarisLabels,
                List.of(polarisContainer),
                volumes,
                "polaris");

        return configurePolarisService(polarisDeployment, polarisLabels);
    }

    protected @NotNull Service configurePolarisService(Deployment polarisDeployment, Map<String, String> polarisLabels) {
        return Service.Builder.create(this, POLARIS_SERVICE_NAME)
                              .metadata(ServiceMetadata.builder()
                                                       .name(POLARIS_SERVICE_NAME)
                                                       .build())
                              .dependsOn(List.of(polarisDeployment))
                              .provisioners(uponDestroyRun(NO_POLARIS_FINALIZERS))
                              .spec(ServiceSpec.builder()
                                               .selector(polarisLabels)
                                               .type("LoadBalancer")
                                               .port(List.of(
                                                       POLARIS_PORTS
                                                            ))
                                               .build())
                              .build();
    }

    public static final void main(String[] unused) {
        System.out.println(PolarisConfig.getProviderCredentials(appProperties(), "test"));
    }
}
