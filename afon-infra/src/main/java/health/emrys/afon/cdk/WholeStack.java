package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.aws.data_aws_iam_policy.DataAwsIamPolicy;
import com.hashicorp.cdktf.providers.aws.eks_cluster.EksCluster;
import com.hashicorp.cdktf.providers.aws.eks_cluster.EksClusterVpcConfig;
import com.hashicorp.cdktf.providers.aws.eks_node_group.EksNodeGroup;
import com.hashicorp.cdktf.providers.aws.eks_node_group.EksNodeGroupScalingConfig;
import com.hashicorp.cdktf.providers.aws.iam_policy.IamPolicy;
import com.hashicorp.cdktf.providers.aws.iam_role.IamRole;
import com.hashicorp.cdktf.providers.aws.iam_role_policy_attachment.IamRolePolicyAttachment;
import com.hashicorp.cdktf.providers.aws.internet_gateway.InternetGateway;
import com.hashicorp.cdktf.providers.aws.provider.AwsProvider;
import com.hashicorp.cdktf.providers.aws.eks_addon.EksAddon;
import com.hashicorp.cdktf.providers.aws.provider.AwsProvider;
import com.hashicorp.cdktf.providers.aws.route_table.RouteTable;
import com.hashicorp.cdktf.providers.aws.route_table.RouteTableRoute;
import com.hashicorp.cdktf.providers.aws.route_table_association.RouteTableAssociation;
import com.hashicorp.cdktf.providers.aws.secretsmanager_secret.SecretsmanagerSecret;
import com.hashicorp.cdktf.providers.aws.secretsmanager_secret_version.SecretsmanagerSecretVersion;
import com.hashicorp.cdktf.providers.aws.subnet.Subnet;
import com.hashicorp.cdktf.providers.aws.vpc.Vpc;
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
import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import software.constructs.Construct;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.infra.Defaults.AWS_REGION;
import static health.emrys.afon.infra.Defaults.CLUSTER_NAME;

/***
 * This currently assumes the EKS cluster is up and running
 */
class WholeStack extends TerraformStack {

    public WholeStack(final Construct scope, final String id) {
        super(scope, id);
        // --- AWS Provider Configuration (Required for EKS Addon) ---
        // 1. Configure the AWS Provider
        AwsProvider.Builder.create(this, "aws")
                           .region(AWS_REGION) // Set your desired AWS Region
                           .build();


        // --- 1. SECRETS MANAGER CREATION ---

        // Create the secret container
        final var applicationSecret = SecretsmanagerSecret.Builder.create(this, "ApplicationSecret")
                                                                  .name("cdktf-app-config-secret")
                                                                  .description("Secret containing application configuration or API keys for EKS.")
                                                                  .tags(Map.of("Name", "EKSAppConfig"))
                                                                  .recoveryWindowInDays(0)
                                                                  .build();

        // Add content to the secret (this is the actual sensitive data)
        // NOTE: In a real-world scenario, you might use an external source for the secret string.
        SecretsmanagerSecretVersion.Builder.create(this, "ApplicationSecretValue")
                                           .secretId(applicationSecret.getId())
                                           .secretString("{\"db_password\":\"s3cur3p@ssw0rd!\"}")
                                           .build();


        // --- 2. VPC and Networking Setup (Same as before) ---
        final Vpc vpc = Vpc.Builder.create(this, "eksVpc")
                                   .cidrBlock("10.0.0.0/16")
                                   .enableDnsSupport(true)
                                   .enableDnsHostnames(true)
                                   .tags(Map.of("Name", "cdktf-eks-vpc")).build();

        final InternetGateway igw = InternetGateway.Builder.create(this, "vpcIgw")
                                                           .vpcId(vpc.getId())
                                                           .tags(Map.of("Name", "cdktf-eks-igw")).build();

        final Subnet publicSubnet1 = Subnet.Builder.create(this, "publicSubnet1")
                                                   .vpcId(vpc.getId())
                                                   .cidrBlock("10.0.1.0/24")
                                                   .availabilityZone(AWS_REGION + "a")
                                                   .mapPublicIpOnLaunch(true)
                                                   .tags(Map.of("Name", "cdktf-eks-public-1")).build();

        final Subnet publicSubnet2 = Subnet.Builder.create(this, "publicSubnet2")
                                                   .vpcId(vpc.getId())
                                                   .cidrBlock("10.0.2.0/24")
                                                   .availabilityZone(AWS_REGION + "b")
                                                   .mapPublicIpOnLaunch(true)
                                                   .tags(Map.of("Name", "cdktf-eks-public-2")).build();

        final RouteTableRoute route = RouteTableRoute.builder()
                                                     .cidrBlock("0.0.0.0/0")
                                                     .gatewayId(igw.getId()).build();

        final RouteTable publicRouteTable = RouteTable.Builder.create(this, "publicRouteTable")
                                                              .vpcId(vpc.getId())
                                                              .route(List.of(route))
                                                              .tags(Map.of("Name", "cdktf-eks-public-rt")).build();

        RouteTableAssociation.Builder.create(this, "subnet1Association")
                                     .subnetId(publicSubnet1.getId())
                                     .routeTableId(publicRouteTable.getId()).build();

        RouteTableAssociation.Builder.create(this, "subnet2Association")
                                     .subnetId(publicSubnet2.getId())
                                     .routeTableId(publicRouteTable.getId()).build();

        final List<String> subnetIds = List.of(publicSubnet1.getId(), publicSubnet2.getId());

        // --- 3. EKS Cluster IAM Role (Same as before) ---

        final String eksAssumeRolePolicy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"eks.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
        final IamRole eksClusterRole = IamRole.Builder.create(this, "eksClusterRole")
                                                      .name("cdktf-eks-cluster-role")
                                                      .assumeRolePolicy(eksAssumeRolePolicy).build();

        final DataAwsIamPolicy eksClusterPolicy = DataAwsIamPolicy.Builder.create(this, "eksClusterPolicy")
                                                                          .name("AmazonEKSClusterPolicy").build();

        IamRolePolicyAttachment.Builder.create(this, "eksClusterRoleAttachment")
                                       .policyArn(eksClusterPolicy.getArn())
                                       .role(eksClusterRole.getName()).build();

        final EksClusterVpcConfig vpcConfig = EksClusterVpcConfig.builder().subnetIds(subnetIds).build();

        var cluster = EksCluster.Builder.create(this, "eksCluster")
                                         .name(CLUSTER_NAME)
                                         .roleArn(eksClusterRole.getArn())
                                         .vpcConfig(vpcConfig)
                                         .version("1.29")
                                         .dependsOn(List.of(eksClusterRole, publicSubnet1, publicSubnet2))
                                         .tags(Map.of("Name", "cdktf-eks")).build();

        NullProvider.Builder.create(this, "nullProvider")
                            .build();
        Resource.Builder.create(this, "kubeconfigUpdater") // FIXED: Use Resource.Builder
                        .dependsOn(List.of(cluster)) // Wait for the cluster to be ready
                        .provisioners(List.of(
                                LocalExecProvisioner.builder()
                                                    .when("create") // Run only when the resource is created
                                                    .command(String.format(
                                                            "aws eks update-kubeconfig --name %s --region %s",
                                                            CLUSTER_NAME,
                                                            AWS_REGION)
                                                            )
                                                    .type("local-exec")
                                                    .build()
                                             ))
                        .build();

        // --- 4. EKS Node Group IAM Role & NEW Custom Policy ---

        final String workerAssumeRolePolicy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
        final IamRole nodeGroupRole = IamRole.Builder.create(this, "eksNodeGroupRole")
                                                     .name("cdktf-eks-nodegroup-role")
                                                     .assumeRolePolicy(workerAssumeRolePolicy).build();

        // ** NEW STEP: Create Custom IAM Policy referencing the Secret ARN **
        // We use String.format to insert the secret's ARN dynamically into the policy JSON.
        final String customSecretPolicyDocument = String.format(
                "{\n" +
                        "  \"Version\": \"2012-10-17\",\n" +
                        "  \"Statement\": [\n" +
                        "    {\n" +
                        "      \"Effect\": \"Allow\",\n" +
                        "      \"Action\": \"secretsmanager:GetSecretValue\",\n" +
                        "      \"Resource\": \"%s\"\n" + // %s will be replaced by the Secret ARN
                        "    }\n" +
                        "  ]\n" +
                        "}", applicationSecret.getArn()
                                                               );

        final IamPolicy customSecretPolicy = IamPolicy.Builder.create(this, "SecretAccessPolicy")
                                                              .name("EKS-Secret-Access-Policy")
                                                              .description("Allows EKS Node Group to read the specific application config secret.")
                                                              .policy(customSecretPolicyDocument)
                                                              .build();

        // ** NEW ATTACHMENT: Attach the custom secret policy to the Node Group role **
        IamRolePolicyAttachment.Builder.create(this, "customSecretPolicyAttachment")
                                       .policyArn(customSecretPolicy.getArn())
                                       .role(nodeGroupRole.getName())
                                       .build();

        // Existing required policy attachments
        final DataAwsIamPolicy workerNodePolicy = DataAwsIamPolicy.Builder.create(this, "workerNodePolicy")
                                                                          .name("AmazonEKSWorkerNodePolicy").build();
        final DataAwsIamPolicy cniPolicy = DataAwsIamPolicy.Builder.create(this, "cniPolicy")
                                                                   .name("AmazonEKS_CNI_Policy").build();
        final DataAwsIamPolicy containerRegistryPolicy = DataAwsIamPolicy.Builder.create(this, "containerRegistryPolicy")
                                                                                 .name("AmazonEC2ContainerRegistryReadOnly").build();

        IamRolePolicyAttachment.Builder.create(this, "workerNodePolicyAttachment")
                                       .policyArn(workerNodePolicy.getArn())
                                       .role(nodeGroupRole.getName()).build();

        IamRolePolicyAttachment.Builder.create(this, "cniPolicyAttachment")
                                       .policyArn(cniPolicy.getArn())
                                       .role(nodeGroupRole.getName()).build();

        IamRolePolicyAttachment.Builder.create(this, "containerRegistryPolicyAttachment")
                                       .policyArn(containerRegistryPolicy.getArn())
                                       .role(nodeGroupRole.getName()).build();

        // --- 5. EKS Managed Node Group Creation (Same as before) ---

        final EksNodeGroupScalingConfig scalingConfig = EksNodeGroupScalingConfig.builder()
                                                                                 .desiredSize(1.0).minSize(1.0).maxSize(2.0).build();

        EksNodeGroup.Builder.create(this, "eksNodeGroup")
                            .clusterName(cluster.getName())
                            .nodeGroupName("default-ng")
                            .subnetIds(subnetIds)
                            .nodeRoleArn(nodeGroupRole.getArn())
                            .instanceTypes(List.of("t3.medium"))
                            .scalingConfig(scalingConfig)
                            .dependsOn(List.of(cluster))
                            .tags(Map.of("Name", "cdktf-eks-nodegroup")).build();

        // 2. Define or update the EKS Addon (e.g., 'coredns')
        EksAddon.Builder.create(this, "vpc-cni")
                        .clusterName(CLUSTER_NAME)
                        .region(AWS_REGION)
                        .addonName("vpc-cni")
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
                              "api_key.txt", "super-secret-key-123",
                              "env.config", "ENVIRONMENT=production\nDEBUG=false"
                                  )
                           )
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
        // This single `Release` resource handles both adding the repository URL and installing the chart.
        Release.Builder.create(this, "secrets-store-csi-driver")
                       .name("csi-secrets-store") // The release name
                       .chart("secrets-store-csi-driver") // The chart name within the repository
                       .repository("https://kubernetes-sigs.github.io/secrets-store-csi-driver/charts")
                       .namespace("kube-system") // Target Kubernetes namespace
                       .timeout(300) // Wait up to 300 seconds for the deployment to stabilize
                       .atomic(true) // If installation fails, completely uninstall the release
                       .build();

    }

    public static void main(String[] args) {
        final App app = new App();
        new WholeStack(app, "helm-nginx-deployment");
        app.synth();
    }
}