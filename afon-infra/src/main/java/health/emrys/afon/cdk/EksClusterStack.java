package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
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
import com.hashicorp.cdktf.providers.aws.route_table.RouteTable;
import com.hashicorp.cdktf.providers.aws.route_table.RouteTableRoute;
import com.hashicorp.cdktf.providers.aws.route_table_association.RouteTableAssociation;
import com.hashicorp.cdktf.providers.aws.secretsmanager_secret.SecretsmanagerSecret;
import com.hashicorp.cdktf.providers.aws.secretsmanager_secret_version.SecretsmanagerSecretVersion;
import com.hashicorp.cdktf.providers.aws.subnet.Subnet;
import com.hashicorp.cdktf.providers.aws.vpc.Vpc;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

// Define a high-level construct to encapsulate the EKS Cluster and its prerequisites
class EksClusterConstruct extends Construct {
    public final EksCluster cluster;

    public EksClusterConstruct(final Construct scope, final String id, final String region) {
        super(scope, id);

        // --- 1. SECRETS MANAGER CREATION ---

        // Create the secret container
        final var applicationSecret = SecretsmanagerSecret.Builder.create(this, "ApplicationSecret")
                                                       .name("cdktf-app-config-secret")
                                                       .description("Secret containing application configuration or API keys for EKS.")
                                                       .tags(Map.of("Name", "EKSAppConfig"))
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
                                                   .availabilityZone(region + "a")
                                                   .mapPublicIpOnLaunch(true)
                                                   .tags(Map.of("Name", "cdktf-eks-public-1")).build();

        final Subnet publicSubnet2 = Subnet.Builder.create(this, "publicSubnet2")
                                                   .vpcId(vpc.getId())
                                                   .cidrBlock("10.0.2.0/24")
                                                   .availabilityZone(region + "b")
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

        this.cluster = EksCluster.Builder.create(this, "eksCluster")
                                         .name("spark-cluster")
                                         .roleArn(eksClusterRole.getArn())
                                         .vpcConfig(vpcConfig)
                                         .version("1.29")
                                         .dependsOn(List.of(eksClusterRole, publicSubnet1, publicSubnet2))
                                         .tags(Map.of("Name", "cdktf-eks")).build();

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
                            .clusterName(this.cluster.getName())
                            .nodeGroupName("default-ng")
                            .subnetIds(subnetIds)
                            .nodeRoleArn(nodeGroupRole.getArn())
                            .instanceTypes(List.of("t3.medium"))
                            .scalingConfig(scalingConfig)
                            .dependsOn(List.of(this.cluster))
                            .tags(Map.of("Name", "cdktf-eks-nodegroup")).build();
    }
}

// Define the main Terraform Stack
public class EksClusterStack extends TerraformStack {
    public EksClusterStack(final Construct scope, final String id) {
        super(scope, id);

        final String awsRegion = "eu-west-2";

        AwsProvider.Builder.create(this, "aws")
                           .region(awsRegion)
                           .build();

        new EksClusterConstruct(this, "myEksCluster", awsRegion);
    }

    // Application entry point for running the CDKTF synthesis
    public static void main(String[] args) {
        final App app = new App();
        new EksClusterStack(app, "cdktf-eks-app");
        app.synth();
    }
}