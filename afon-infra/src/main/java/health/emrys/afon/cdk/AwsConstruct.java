package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.LocalExecProvisioner;
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
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

import static health.emrys.afon.infra.Defaults.AWS_REGION;
import static health.emrys.afon.infra.Defaults.CLUSTER_NAME;
import static java.lang.String.format;

public class AwsConstruct extends Construct implements CloudProvider {
    private final DataAwsEksClusterAuth eksAuthData;
    private final EksCluster            cluster;
    private final List<Resource>        eksDependOn;

    public AwsConstruct(final Construct scope, final String id) {
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

        cluster = EksCluster.Builder.create(this, "eksCluster")
                                    .name(CLUSTER_NAME)
                                    .roleArn(eksClusterRole.getArn())
                                    .vpcConfig(vpcConfig)
                                    .version("1.31")
                                    .dependsOn(List.of(eksClusterRole, publicSubnet1, publicSubnet2))
                                    .tags(Map.of("Name", "cdktf-eks")).build();

        // if one depends_on this, then the cluster should be running
        eksAuthData = DataAwsEksClusterAuth.Builder.create(this, "eksClusterAuthData")
                                                   .name(cluster.getName()) // Uses the name of the EKS cluster
                                                   .build();

        final String workerAssumeRolePolicy = """
                {"Version":"2012-10-17",
                "Statement":[{
                        "Effect":"Allow",
                        "Principal":{
                            "Service": "ec2.amazonaws.com"
                        },
                        "Action":"sts:AssumeRole"
                    },
                    {
                        "Effect":"Allow",
                        "Principal":{
                            "Service": "elasticloadbalancing.amazonaws.com"
                        },
                        "Action":"sts:AssumeRole"
                    }
                ] }""";
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
                                                                                 .desiredSize(3.0)
                                                                                 .minSize(3.0)
                                                                                 .maxSize(3.0)
                                                                                 .build();

        Resource newKubeConfig = Resource.Builder.create(this, "kubeconfigUpdater")
                                                 .dependsOn(List.of(cluster, eksAuthData)) // Wait for the cluster to be ready
                                                 .provisioners(List.of(
                                                         LocalExecProvisioner.builder()
                                                                             .when("create") // Run only when the resource is created
                                                                             .command(format(
                                                                                              "aws eks update-kubeconfig --name %s --region %s",
                                                                                              CLUSTER_NAME,
                                                                                              AWS_REGION)
                                                                                     )
                                                                             .type("local-exec")
                                                                             .build()
                                                                      ))
                                                 .build();

        eksDependOn = List.of(newKubeConfig);

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


    }

    @Override
    public List<Resource> getDependables() {
        return eksDependOn; // List.of(cluster, eksAuthData);
    }

    @Override
    public String getBase64CertData() {
        return cluster.getCertificateAuthority().get(0).getData();
    }

    @Override
    public String getEndpoint() {
        return cluster.getEndpoint();
    }

    @Override
    public String getToken() {
        return eksAuthData.getToken();
    }
}
