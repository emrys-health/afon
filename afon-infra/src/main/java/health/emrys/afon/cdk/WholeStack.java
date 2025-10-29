package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.aws.provider.AwsProvider;
import com.hashicorp.cdktf.providers.aws.eks_addon.EksAddon;
import com.hashicorp.cdktf.providers.aws.provider.AwsProvider;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.helm.release.ReleaseSet;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProvider;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProviderConfig;
import com.hashicorp.cdktf.providers.kubernetes.deployment.Deployment;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentMetadata;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpec;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecSelector;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplate;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateMetadata; // NEW: Correct import for template metadata
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpec;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainer;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerVolumeMount;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecVolume;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecVolumeSecret;
import com.hashicorp.cdktf.providers.kubernetes.network_policy.*;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import software.constructs.Construct;

import java.util.Base64;
import java.util.List;
import java.util.Map;

// The main stack where we define our infrastructure
class WholeStack extends TerraformStack {

    public WholeStack(final Construct scope, final String id) {
        super(scope, id);

        // --- AWS Provider Configuration (Required for EKS Addon) ---
        // 1. Configure the AWS Provider
        AwsProvider.Builder.create(this, "aws")
                           .region("us-west-2") // Set your desired AWS Region
                           .build();

        // 2. Define or update the EKS Addon (e.g., 'coredns')
        final String clusterName = "my-cdktf-eks-cluster-name"; // <<< REPLACE WITH YOUR EKS CLUSTER NAME

        EksAddon.Builder.create(this, "coreDnsAddon")
                        .clusterName(clusterName)
                        .addonName("coredns")
                        // To update the addon, simply change the addonVersion property
                        .addonVersion("v1.10.1-eksbuild.2")
                        // Setting resolveConflicts to OVERWRITE forces the update even if manual changes exist
                        .resolveConflictsOnUpdate("OVERWRITE")
                        .build();


        // --- Kubernetes and Helm Provider Configuration (Original Code) ---

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

        Deployment.Builder.create(this, "secretAppDeployment")
                          .metadata(DeploymentMetadata.builder()
                                                      .name("secret-app-deployment")
                                                      .labels(labels)
                                                      .build())
                          .spec(DeploymentSpec.builder()
                                              .selector(DeploymentSpecSelector.builder().matchLabels(labels).build())
                                              .replicas("1")
                                              .template(DeploymentSpecTemplate.builder()
                                                                              .metadata(DeploymentSpecTemplateMetadata.builder().labels(labels).build()) // FIX: Correctly using DeploymentSpecTemplateMetadata builder
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

        // 5.1. Define a NetworkPolicy to control egress traffic for the Deployment
        NetworkPolicy.Builder.create(this, "secretAppEgressPolicy")
                             .metadata(NetworkPolicyMetadata.builder()
                                                            .name("allow-only-specific-egress")
                                                            .namespace("default") // Assuming the Deployment is in the default namespace
                                                            .build())
                             .spec(NetworkPolicySpec.builder()
                                                    // Target the pods created by the Deployment using the same labels
                                                    .podSelector(NetworkPolicySpecPodSelector.builder().matchLabels(labels).build())

                                                    // Set policy type to Egress (outbound). This implicitly denies all other outbound traffic.
                                                    .policyTypes(List.of("Egress"))

                                                    // Define the allowed outbound connections
                                                    .egress(List.of(NetworkPolicySpecEgress.builder()
                                                                                           .to(List.of(
                                                                                                   // Rule 1: Allow traffic to a specific external service IP (e.g., a Database)
                                                                                                   NetworkPolicySpecEgressTo.builder()
                                                                                                                            .ipBlock(NetworkPolicySpecEgressToIpBlock.builder()
                                                                                                                                                                     .cidr("203.0.113.1/32") // Use /32 for a single IP
                                                                                                                                                                     .build())
                                                                                                                            .build(),
                                                                                                   // Rule 2: Allow traffic to a range of IPs (e.g., a specific API service range)
                                                                                                   NetworkPolicySpecEgressTo.builder()
                                                                                                                            .ipBlock(NetworkPolicySpecEgressToIpBlock.builder()
                                                                                                                                                                     .cidr("198.51.100.0/24") // Use CIDR notation for ranges
                                                                                                                                                                     .build())
                                                                                                                            .build()
                                                                                                      ))
                                                                                           // By omitting the 'ports' property here, all TCP/UDP ports are allowed to the targets defined in 'to'.
                                                                                           // To restrict to port 443 (HTTPS), you would add:
                                                                                           // .ports(List.of(NetworkPolicySpecEgressPort.builder().port("443").protocol("TCP").build()))
                                                                                           .build()
                                                                   ))
                                                    .build())
                             .build();


        // 6. Configure the Helm Provider
        HelmProvider.Builder.create(this, "helm")
                            // No explicit configuration needed here, but you could add experimental features
                            // or specific connection details if needed.
                            .build();


        // 7. Install a Helm Chart (equivalent to 'helm install')
        Release.Builder.create(this, "redisRelease")
                       .name("cdktf-redis-prod") // The release name
                       .chart("redis") // The chart name within the repository
                       .repository("https://charts.bitnami.com/bitnami")
                       .version("18.6.0") // Specify a stable version
                       .namespace("default") // Target Kubernetes namespace
                       .createNamespace(true) // Automatically create the namespace if it does not exist
                       .timeout(300) // Wait up to 300 seconds for the deployment to stabilize
                       .atomic(true) // If installation fails, completely uninstall the release

                       // Override chart values using the 'set' property (equivalent to '--set key=value')
                       .set(List.of(
                               ReleaseSet.builder()
                                         .name("master.persistence.enabled")
                                         .value("false")
                                         .build(),
                               ReleaseSet.builder()
                                         .name("replica.replicaCount")
                                         .value("2")
                                         .build()
                                   ))
                       // Override values using a map (equivalent to setting 'values.yaml' content)
                       .values(List.of(
                               // Using a full JSON string for values is common for complex overrides
                               "{\"architecture\":\"standalone\"}"
                                      ))
                       .build();

    }

    public static void main(String[] args) {
        final App app = new App();
        new WholeStack(app, "helm-redis-deployment");
        app.synth();
    }
}