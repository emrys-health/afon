package health.emrys.afon.cdk;

import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainer;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerVolumeMount;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecVolume;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecVolumeSecret;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProvider;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import health.emrys.afon.config.AfonPropertiesLoader;
import org.jetbrains.annotations.NotNull;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.config.AfonProperties.AZURE_CLIENT_ID;
import static health.emrys.afon.config.AfonProperties.AZURE_CLIENT_SECRET;
import static health.emrys.afon.config.AfonProperties.AZURE_TENANT_ID;
import static health.emrys.afon.infra.Defaults.POLARIS_SERVICE_NAME;
import static java.lang.String.format;

public class PolarisConfig extends Construct {

    public static final String        SECRET_NAME = "my-app-secret";
    public static final String        POLARIS_ENV = "polaris_env.sh";
    private final       CloudProvider cloudProvider;

    public PolarisConfig(@NotNull Construct scope, @NotNull String id, CloudProvider cloudProvider) {
        super(scope, id);
        this.cloudProvider = cloudProvider;

        // Configure the Kubernetes Provider
        configureK8s(cloudProvider);

        // Define a Kubernetes Secret
        // The data must be Base64 encoded. Each key in the secret will become a file in the volume mount.
        AfonPropertiesLoader loader     = new AfonPropertiesLoader();
        Properties           properties = loader.getProperties();
        Secret.Builder.create(this, "mySecret")
                      .dependsOn(cloudProvider.getDependables())
                      .metadata(SecretMetadata.builder().name(SECRET_NAME).build())
                      .data(Map.of(
                              POLARIS_ENV, format("""
                                                          export POLARIS_ID=root
                                                          export POLARIS_SECRET=s3cr3t
                                                          
                                                          export %s=%s
                                                          export %s=%s
                                                          export %s=%s
                                                          export %s=%s
                                                          export %s=%s
                                                          """,
                                                  AWS_ACCESS_KEY_ID.getPropertyName(), AWS_ACCESS_KEY_ID.getValueFrom(properties),
                                                  AWS_SECRET_ACCESS_KEY.getPropertyName(), AWS_SECRET_ACCESS_KEY.getValueFrom(properties),
                                                  AZURE_CLIENT_ID.getPropertyName(), AZURE_CLIENT_ID.getValueFrom(properties),
                                                  AZURE_TENANT_ID.getPropertyName(), AZURE_TENANT_ID.getValueFrom(properties),
                                                  AZURE_CLIENT_SECRET.getPropertyName(), AZURE_CLIENT_SECRET.getValueFrom(properties)
                                                 )
                                  )
                           )
                      .type("Opaque")
                      .build();
    }

    protected void configureK8s(CloudProvider cloudProvider) {
        String base64CertData = cloudProvider.getBase64CertData();
        String encodedCert    = com.hashicorp.cdktf.Fn.base64decode(base64CertData);
        KubernetesProvider.Builder.create(this, "kubernetes")
                                  .host(cloudProvider.getClusterEndpoint())
                                  .clusterCaCertificate(encodedCert)
                                  .token(cloudProvider.getToken()) // Dynamically generated token
                                  .build();
    }

    public Service deployPolaris(List<Resource> eksDependOn) {
        String secretVolume = "secret-volume";
        List<DeploymentSpecTemplateSpecVolume> volumes = List.of(DeploymentSpecTemplateSpecVolume.builder()
                                                                                                 .name(secretVolume)
                                                                                                 .secret(DeploymentSpecTemplateSpecVolumeSecret.builder()
                                                                                                                                               .secretName(SECRET_NAME)
                                                                                                                                               .build())
                                                                                                 .build());

        String secretsDir = "/etc/app-secrets";

        List<DeploymentSpecTemplateSpecContainerVolumeMount> mounts = List.of(DeploymentSpecTemplateSpecContainerVolumeMount.builder()
                                                                                                                            .name(secretVolume)
                                                                                                                            .mountPath(secretsDir)
                                                                                                                            .readOnly(true)
                                                                                                                            .build());

        List<String> commands = List.of("/bin/bash",
                                        "-c",
                                        format("source %s/%s && /opt/jboss/container/java/run/run-java.sh",
                                               secretsDir, POLARIS_ENV));
        DeploymentSpecTemplateSpecContainer polarisContainer = DeploymentSpecTemplateSpecContainer.builder()
                                                                                                  .name("polaris")
                                                                                                  .image("apache/polaris:1.1.0-incubating")
//                                                                                                  .env(envVars)
                                                                                                  .command(commands)
                                                                                                  .volumeMount(mounts)
                                                                                                  .build();
        final Map<String, String> polarisLabels = Map.of("app", "polaris");
        var polarisDeployment = DeploymentUtils.deploy(
                this,
                eksDependOn,
                polarisLabels,
                List.of(polarisContainer),
                volumes,
                "polaris");

        return Service.Builder.create(this, POLARIS_SERVICE_NAME)
                              .metadata(ServiceMetadata.builder()
                                                       .name(POLARIS_SERVICE_NAME)
                                                       .build())
                              .dependsOn(List.of(polarisDeployment))
                              .spec(ServiceSpec.builder()
                                               .selector(polarisLabels)
                                               .type("LoadBalancer")
                                               .port(List.of(
                                                       ServiceSpecPort.builder()
                                                                      .name("web")
                                                                      .port(8181)
                                                                      .targetPort("8181")
                                                                      .protocol("TCP")
                                                                      .build()
                                                            ))
                                               .build())
                              .build();
    }
}
