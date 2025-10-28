package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.aws.provider.AwsProvider;
import com.hashicorp.cdktf.providers.kubernetes.deployment.*;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProvider;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import software.constructs.Construct;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.infra.Defaults.AWS_REGION;

public class Deployment extends TerraformStack {
    public Deployment(final Construct scope, final String id) {
        super(scope, id);
        AwsProvider.Builder.create(this, "aws")
                           .region(AWS_REGION) // Set your desired AWS Region
                           .build();


        // 3. Configure the Kubernetes Provider
        String configPath = System.getProperty("user.home") + "/.kube/config";
        KubernetesProvider.Builder.create(this, "kubernetes")
                                  .configPath(configPath)
                                  .build();

        // 4. Define a Kubernetes Secret
        // The data must be Base64 encoded. Each key in the secret will become a file in the volume mount.
        Secret.Builder.create(this, "mySecret")
                      .metadata(SecretMetadata.builder().name("my-app-secret").build())
                      .data(Map.of(
                              "api_key.txt", Base64.getEncoder().encodeToString("super-secret-key-123".getBytes()),
                              "env.config", Base64.getEncoder().encodeToString("ENVIRONMENT=production\nDEBUG=false".getBytes())
                                  ))
                      .type("Opaque")
                      .build();

        // 5. Define a Deployment that mounts the Secret as a Volume
        final Map<String, String> labels = Map.of("app", "my-secret-app");

        com.hashicorp.cdktf.providers.kubernetes.deployment.Deployment.Builder.create(this, "secretAppDeployment")
                                                                              .metadata(DeploymentMetadata.builder()
                                                      .name("secret-app-deployment")
                                                      .labels(labels)
                                                      .build())
                                                                              .spec(DeploymentSpec.builder()
                                              .selector(DeploymentSpecSelector.builder().matchLabels(labels).build())
                                              .replicas("1")
                                              .template(DeploymentSpecTemplate.builder()
                                                                              .metadata(DeploymentSpecTemplateMetadata.builder().labels(labels).build())
                                                                              .spec(DeploymentSpecTemplateSpec.builder()
                                                                                                              .container(List.of(DeploymentSpecTemplateSpecContainer.builder()
                                                                                                                                                                    .name("main-app-container")
                                                                                                                                                                    .image("nginx:latest") // Example container image
                                                                                                                                                                    .volumeMount(List.of(DeploymentSpecTemplateSpecContainerVolumeMount.builder()
                                                                                                                                                                                                                                       .name("secret-volume") // Must match the volume name defined below
                                                                                                                                                                                                                                       .mountPath("/etc/app-secrets") // The directory where secret files will appear
                                                                                                                                                                                                                                       .readOnly(true)
                                                                                                                                                                                                                                       .build()))
                                                                                                                                                                    .build()))
                                                                                                              .volume(List.of(DeploymentSpecTemplateSpecVolume.builder()
                                                                                                                                                              .name("secret-volume") // The volume name, referenced above
                                                                                                                                                              .secret(DeploymentSpecTemplateSpecVolumeSecret.builder()
                                                                                                                                                                                                            .secretName("my-app-secret") // The name of the Kubernetes Secret resource to mount
                                                                                                                                                                                                            .build())
                                                                                                                                                              .build()))
                                                                                                              .build())
                                                                              .build())
                                              .build())
                                                                              .build();

    }
    public static void main(String[] args) {
        final App app = new App();
        new Deployment(app, "use-secrets-deployment");
        app.synth();
    }
}
