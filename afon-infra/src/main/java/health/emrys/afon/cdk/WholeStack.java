package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.aws.data_aws_eks_cluster_auth.DataAwsEksClusterAuth;
import com.hashicorp.cdktf.providers.aws.data_aws_iam_policy.DataAwsIamPolicy;
import com.hashicorp.cdktf.providers.aws.eks_addon.EksAddon;
import com.hashicorp.cdktf.providers.aws.eks_cluster.EksCluster;
import com.hashicorp.cdktf.providers.aws.eks_cluster.EksClusterVpcConfig;
import com.hashicorp.cdktf.providers.aws.eks_node_group.EksNodeGroup;
import com.hashicorp.cdktf.providers.aws.eks_node_group.EksNodeGroupScalingConfig;
import com.hashicorp.cdktf.providers.aws.iam_policy.IamPolicy;
import com.hashicorp.cdktf.providers.aws.iam_role.IamRole;
import com.hashicorp.cdktf.providers.aws.iam_role_policy_attachment.IamRolePolicyAttachment;
import com.hashicorp.cdktf.providers.aws.internet_gateway.InternetGateway;
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
import com.hashicorp.cdktf.providers.kubernetes.deployment.*;
import com.hashicorp.cdktf.providers.kubernetes.deployment.Deployment;
import com.hashicorp.cdktf.providers.kubernetes.network_policy.*;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProvider;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import io.kubernetes.client.proto.V1;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

import static health.emrys.afon.infra.Defaults.AWS_REGION;
import static health.emrys.afon.infra.Defaults.CLUSTER_NAME;

/***
 * Creates the markup to create the full stack in AWS.
 * Note: it does not create anything other than JSON files!
 * You need to run tofu on those files for the infra to be provisioned.
 * Don't forget to
 * <UL>
 *     <LI>
 *          set environment variables, something like <pre>KUBE_CONFIG_PATH=/home/henryp/.kube/config</pre>
 *     </LI>
 *      <LI>
 *          <pre>unalias aws</pre> if you're using the Dockerized version and put the actual binary in your <pre>PATH</pre>
 *      </LI>
 * </UL>
 * when you run the output
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

        final String eksAssumeRolePolicy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"eks.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";
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

        // if one depends_on this, then the cluster should be running
        DataAwsEksClusterAuth eksAuthData = DataAwsEksClusterAuth.Builder.create(this, "eksClusterAuthData")
                                                                         .name(cluster.getName()) // Uses the name of the EKS cluster
                                                                         .build();

        NullProvider.Builder.create(this, "nullProvider")
                            .build();

//        Resource newKubeConfig = Resource.Builder.create(this, "kubeconfigUpdater")
//                                                 .dependsOn(List.of(cluster, eksAuthData)) // Wait for the cluster to be ready
//                                                 .provisioners(List.of(
//                                                         LocalExecProvisioner.builder()
//                                                                             .when("create") // Run only when the resource is created
//                                                                             .command(String.format(
//                                                                                              "aws eks update-kubeconfig --name %s --region %s",
//                                                                                              CLUSTER_NAME,
//                                                                                              AWS_REGION)
//                                                                                     )
//                                                                             .type("local-exec")
//                                                                             .build()
//                                                                      ))
//                                                 .build();

        var eksDependOn = List.of(cluster, eksAuthData); //List.of(newKubeConfig);

        // --- 4. EKS Node Group IAM Role & NEW Custom Policy ---

        final String workerAssumeRolePolicy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";
        final IamRole nodeGroupRole = IamRole.Builder.create(this, "eksNodeGroupRole")
                                                     .name("cdktf-eks-nodegroup-role")
                                                     .assumeRolePolicy(workerAssumeRolePolicy).build();

        // ** NEW STEP: Create Custom IAM Policy referencing the Secret ARN **
        // We use String.format to insert the secret's ARN dynamically into the policy JSON.
        // %s will be replaced by the Secret ARN
        final String customSecretPolicyDocument = String.format(
                """
                        {
                          "Version": "2012-10-17",
                          "Statement": [
                            {
                              "Effect": "Allow",
                              "Action": "secretsmanager:GetSecretValue",
                              "Resource": "%s"
                            }
                          ]
                        }""", applicationSecret.getArn()
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
                            .dependsOn(eksDependOn)
                            .tags(Map.of("Name", "cdktf-eks-nodegroup")).build();

        // 2. Define or update the EKS Addon (e.g., 'coredns')
        EksAddon.Builder.create(this, "vpc-cni")
                        .clusterName(CLUSTER_NAME)
                        .region(AWS_REGION)
                        .addonName("vpc-cni")
                        .dependsOn(eksDependOn)
                        // Setting resolveConflicts to OVERWRITE forces the update even if manual changes exist
                        .resolveConflictsOnUpdate("OVERWRITE")
                        .build();


        // --- Kubernetes and Helm Provider Configuration (Original Code) ---

        // 3. Configure the Kubernetes Provider
        String configPath     = System.getProperty("user.home") + "/.kube/config";
        String base64CertData = cluster.getCertificateAuthority().get(0).getData();
        String encodedCert    = com.hashicorp.cdktf.Fn.base64decode(base64CertData);
        KubernetesProvider kubernetesProvider = KubernetesProvider.Builder.create(this, "kubernetes")
                                                                          // Direct EKS Connection Configuration:
                                                                          .host(cluster.getEndpoint())
                                                                          .clusterCaCertificate(encodedCert)
                                                                          .token(eksAuthData.getToken()) // Dynamically generated token
                                                                          .build();
//        kubernetesProvider.addOverride("depends_on", List.of(eksDependOn));

        // 4. Define a Kubernetes Secret
        // The data must be Base64 encoded. Each key in the secret will become a file in the volume mount.
        String secretName = "my-app-secret";
        Secret.Builder.create(this, "mySecret")
                      .dependsOn(eksDependOn)
                      .metadata(SecretMetadata.builder().name(secretName).build())
                      .data(Map.of(
                              "api_key.txt", "super-secret-key-123",
                              "env.config", "ENVIRONMENT=production\nDEBUG=false"
                                  )
                           )
                      .type("Opaque")
                      .build();

        // 5. Define a Deployment that mounts the Secret as a Volume
        final Map<String, String> labels = Map.of("app", "my-secret-app");

        String secretVolume = "secret-volume";
        List<DeploymentSpecTemplateSpecVolume> volumes = List.of(DeploymentSpecTemplateSpecVolume.builder()
                                                                                                 .name(secretVolume)
                                                                                                 .secret(DeploymentSpecTemplateSpecVolumeSecret.builder()
                                                                                                                                               .secretName(secretName)
                                                                                                                                               .build())
                                                                                                 .build());
        String secretsDir = "/etc/app-secrets";
        List<DeploymentSpecTemplateSpecContainerVolumeMount> mounts = List.of(DeploymentSpecTemplateSpecContainerVolumeMount.builder()
                                                                                                                            .name(secretVolume) // Must match the volume name defined below
                                                                                                                            .mountPath(secretsDir) // The directory where secret files will appear
                                                                                                                            .readOnly(true)
                                                                                                                            .build());

        List<? extends DeploymentSpecTemplateSpecContainerEnv> envVars = List.of(
                DeploymentSpecTemplateSpecContainerEnv.builder()
                                                      .name("SPARK_CONF_DIR")
                                                      .value(secretsDir)
                                                      .build()
       );
        List<DeploymentSpecTemplateSpecContainer> containers = List.of(DeploymentSpecTemplateSpecContainer.builder()
                                                                                                          .name("jupyter")
                                                                                                          .image("jupyter/pyspark-notebook:x86_64-spark-3.5.0")
                                                                                                          .env(envVars)
                                                                                                          .volumeMount(mounts)
                                                                                                          .build());
        Deployment.Builder.create(this, "jupyterDeployment")
                          .dependsOn(eksDependOn)
                          .metadata(DeploymentMetadata.builder()
                                                      .name("jupyter-deployment")
                                                      .labels(labels)
                                                      .build())
                          .spec(DeploymentSpec.builder()
                                              .selector(DeploymentSpecSelector.builder().matchLabels(labels).build())
                                              .replicas("1")
                                              .template(DeploymentSpecTemplate.builder()
                                                                              .metadata(DeploymentSpecTemplateMetadata.builder().labels(labels).build()) // FIX: Correctly using DeploymentSpecTemplateMetadata builder
                                                                              .spec(DeploymentSpecTemplateSpec.builder()
                                                                                                              .container(containers)
                                                                                                              .volume(volumes)
                                                                                                              .build())
                                                                              .build())
                                              .build())
                          .build();

        // 5.1. Define a NetworkPolicy to control egress traffic for the Deployment
        List<NetworkPolicySpecEgressPorts> dnsPorts = List.of(
                // Allow UDP/53 for standard DNS lookups
                NetworkPolicySpecEgressPorts.builder().protocol("UDP").port("53").build(),
                // Allow TCP/53 for DNS zone transfers or large responses
                NetworkPolicySpecEgressPorts.builder().protocol("TCP").port("53").build()
        );
        List<NetworkPolicySpecEgressPorts> webPorts = List.of(
                // Allow common HTTP/HTTPS to the external CIDR
                NetworkPolicySpecEgressPorts.builder().protocol("TCP").port("80").build(),
                NetworkPolicySpecEgressPorts.builder().protocol("TCP").port("443").build()
        );
        NetworkPolicySpecEgressTo dnsNetworkPolicy = NetworkPolicySpecEgressTo
                .builder() // 1. Select the 'kube-system' namespace (common EKS label for system namespaces)
                .namespaceSelector(NetworkPolicySpecEgressToNamespaceSelector.builder()
                                                                             .matchLabels(Map.of("kubernetes.io/metadata.name", "kube-system"))
                                                                             .build())
                // 2. Select the CoreDNS Pods within that namespace
                .podSelector(NetworkPolicySpecEgressToPodSelector.builder()
                                                                 .matchLabels(Map.of("k8s-app", "kube-dns"))
                                                                 .build())
                .build();
        String bbcNews = "151.101.0.0/16";
        List<NetworkPolicySpecEgressTo> allowedIPs = List.of(
                NetworkPolicySpecEgressTo.builder()
                                         .ipBlock(NetworkPolicySpecEgressToIpBlock.builder()
                                                                                  .cidr(bbcNews)
                                                                                  .build())
                                         .build()
        );
        NetworkPolicy.Builder.create(this, "secretAppEgressPolicy")
                             .metadata(NetworkPolicyMetadata.builder()
                                                            .name("allow-only-specific-egress")
                                                            .namespace("default")
                                                            .build())
                             .spec(NetworkPolicySpec.builder()
                                                    .podSelector(NetworkPolicySpecPodSelector.builder().matchLabels(labels).build())
                                                    .policyTypes(List.of("Egress"))
                                                    // Policy requires two separate Egress rules, as ports must be defined per rule.
                                                    .egress(List.of(
                                                            // Egress Rule 1: External CIDR (151.101.0.0/16) - No change
                                                            NetworkPolicySpecEgress.builder()
                                                                                   .to(allowedIPs)
                                                                                   // Ports are defined here on the Egress builder
                                                                                   .ports(webPorts)
                                                                                   .build(),

                                                            // Egress Rule 2: DNS Traffic (Target CoreDNS Pods directly)
                                                            NetworkPolicySpecEgress.builder()
                                                                                   .to(List.of(dnsNetworkPolicy))
                                                                                   // Ports are defined here on the Egress builder
                                                                                   .ports(dnsPorts)
                                                                                   .build()
                                                                   ))
                                                    .build())
                             .build(); // NOTE: Removed dependency on kubeconfigUpdater

        // 6. Configure the Helm Provider
        HelmProvider.Builder.create(this, "helm")
                            // No explicit configuration needed here, but you could add experimental features
                            // or specific connection details if needed.
                            .build();

        Release.Builder.create(this, "calicoOperator")
                       .name("tigera-operator")
                       .chart("tigera-operator")
                       .repository("https://docs.tigera.io/calico/charts")
                       .namespace("tigera-operator") // Standard namespace for the operator
                       .createNamespace(true)
                       .version("3.31.0") // Using a recent stable version
                       .atomic(true)
                       .build();

        // 7. Install a Helm Chart (equivalent to 'helm install')
        // This single `Release` resource handles both adding the repository URL and installing the chart.
        Release.Builder.create(this, "secrets-store-csi-driver")
                       .name("csi-secrets-store") // The release name
                       .chart("secrets-store-csi-driver") // The chart name within the repository
                       .repository("https://kubernetes-sigs.github.io/secrets-store-csi-driver/charts")
                       .namespace("kube-system") // Target Kubernetes namespace
                       .dependsOn(eksDependOn)
                       .timeout(300) // Wait up to 300 seconds for the deployment to stabilize
                       .atomic(true) // If installation fails, completely uninstall the release
                       .build();
    }

    public static void main(String[] args) {
        System.out.println(new java.util.Date());
        final App app = new App();
        new WholeStack(app, "jupyter-deployment");
        app.synth();
    }
}