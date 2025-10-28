package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;

import com.hashicorp.cdktf.providers.aws.data_aws_iam_policy.DataAwsIamPolicy;
import com.hashicorp.cdktf.providers.aws.eks_cluster.EksCluster;
import com.hashicorp.cdktf.providers.aws.eks_cluster.EksClusterVpcConfig;
import com.hashicorp.cdktf.providers.aws.eks_node_group.EksNodeGroup;
import com.hashicorp.cdktf.providers.aws.eks_node_group.EksNodeGroupScalingConfig;
import com.hashicorp.cdktf.providers.aws.iam_role.IamRole;
import com.hashicorp.cdktf.providers.aws.iam_role_policy_attachment.IamRolePolicyAttachment;
import com.hashicorp.cdktf.providers.aws.internet_gateway.InternetGateway;
import com.hashicorp.cdktf.providers.aws.provider.AwsProvider;
import com.hashicorp.cdktf.providers.aws.route_table.RouteTable;
import com.hashicorp.cdktf.providers.aws.route_table.RouteTableRoute;
import com.hashicorp.cdktf.providers.aws.route_table_association.RouteTableAssociation;
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

        // --- 1. VPC and Networking Setup (Minimal for EKS) ---

        // Create a VPC
        final Vpc vpc = Vpc.Builder.create(this, "eksVpc")
                                   .cidrBlock("10.0.0.0/16")
                                   .enableDnsSupport(true)
                                   .enableDnsHostnames(true)
                                   .tags(Map.of("Name", "cdktf-eks-vpc"))
                                   .build();

        // Create an Internet Gateway (required for public EKS endpoints)
        final InternetGateway igw = InternetGateway.Builder.create(this, "vpcIgw")
                                                           .vpcId(vpc.getId())
                                                           .tags(Map.of("Name", "cdktf-eks-igw"))
                                                           .build();

        // Create two public subnets in different availability zones
        final Subnet publicSubnet1 = Subnet.Builder.create(this, "publicSubnet1")
                                                   .vpcId(vpc.getId())
                                                   .cidrBlock("10.0.1.0/24")
                                                   // Note: We use the region variable to ensure AZs are correct.
                                                   .availabilityZone(region + "a")
                                                   .mapPublicIpOnLaunch(true)
                                                   .tags(Map.of("Name", "cdktf-eks-public-1"))
                                                   .build();

        final Subnet publicSubnet2 = Subnet.Builder.create(this, "publicSubnet2")
                                                   .vpcId(vpc.getId())
                                                   .cidrBlock("10.0.2.0/24")
                                                   .availabilityZone(region + "b")
                                                   .mapPublicIpOnLaunch(true)
                                                   .tags(Map.of("Name", "cdktf-eks-public-2"))
                                                   .build();

        // Define the route for the Internet Gateway
        final RouteTableRoute route = RouteTableRoute.builder()
                                                     .cidrBlock("0.0.0.0/0")
                                                     .gatewayId(igw.getId())
                                                     .build();

        // Create a public route table and add a route to the Internet Gateway
        final RouteTable publicRouteTable = RouteTable.Builder.create(this, "publicRouteTable")
                                                              .vpcId(vpc.getId())
                                                              .route(List.of(route))
                                                              .tags(Map.of("Name", "cdktf-eks-public-rt"))
                                                              .build();

        // Associate the subnets with the public route table
        RouteTableAssociation.Builder.create(this, "subnet1Association")
                                     .subnetId(publicSubnet1.getId())
                                     .routeTableId(publicRouteTable.getId())
                                     .build();

        RouteTableAssociation.Builder.create(this, "subnet2Association")
                                     .subnetId(publicSubnet2.getId())
                                     .routeTableId(publicRouteTable.getId())
                                     .build();

        // List of subnet IDs for EKS
        final List<String> subnetIds = List.of(publicSubnet1.getId(), publicSubnet2.getId());

        // --- 2. EKS Cluster IAM Role ---

        // Define the Assume Role Policy for the EKS service
        final String eksAssumeRolePolicy = "{\n" +
                "  \"Version\": \"2012-10-17\",\n" +
                "  \"Statement\": [\n" +
                "    {\n" +
                "      \"Effect\": \"Allow\",\n" +
                "      \"Principal\": { \"Service\": \"eks.amazonaws.com\" },\n" +
                "      \"Action\": \"sts:AssumeRole\"\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        // Create the EKS Cluster role
        final IamRole eksClusterRole = IamRole.Builder.create(this, "eksClusterRole")
                                                      .name("cdktf-eks-cluster-role")
                                                      .assumeRolePolicy(eksAssumeRolePolicy)
                                                      .build();

        // Attach the required AmazonEKSClusterPolicy
        final DataAwsIamPolicy eksClusterPolicy = DataAwsIamPolicy.Builder.create(this, "eksClusterPolicy")
                                                                          .name("AmazonEKSClusterPolicy")
                                                                          .build();

        IamRolePolicyAttachment.Builder.create(this, "eksClusterRoleAttachment")
                                       .policyArn(eksClusterPolicy.getArn())
                                       .role(eksClusterRole.getName())
                                       .build();

        // --- 3. EKS Cluster Creation ---

        final EksClusterVpcConfig vpcConfig = EksClusterVpcConfig.builder()
                                                                 .subnetIds(subnetIds)
                                                                 .build();

        this.cluster = EksCluster.Builder.create(this, "eksCluster")
                                         .name("cdktf-eks-cluster")
                                         .roleArn(eksClusterRole.getArn())
                                         .vpcConfig(vpcConfig)
                                         .version("1.29") // Specify a Kubernetes version
                                         // We use .dependsOn() to explicitly handle dependencies
                                         .dependsOn(List.of(eksClusterRole, publicSubnet1, publicSubnet2))
                                         .tags(Map.of("Name", "cdktf-eks"))
                                         .build();

        // --- 4. EKS Node Group IAM Role ---

        // Define the Assume Role Policy for the EC2 instances in the Node Group
        final String workerAssumeRolePolicy = "{\n" +
                "  \"Version\": \"2012-10-17\",\n" +
                "  \"Statement\": [\n" +
                "    {\n" +
                "      \"Effect\": \"Allow\",\n" +
                "      \"Principal\": { \"Service\": \"ec2.amazonaws.com\" },\n" +
                "      \"Action\": \"sts:AssumeRole\"\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        // Create the Node Group role
        final IamRole nodeGroupRole = IamRole.Builder.create(this, "eksNodeGroupRole")
                                                     .name("cdktf-eks-nodegroup-role")
                                                     .assumeRolePolicy(workerAssumeRolePolicy)
                                                     .build();

        // Attach required policies to the Node Group role (Worker Node, CNI, and Registry Read Only)
        final DataAwsIamPolicy workerNodePolicy = DataAwsIamPolicy.Builder.create(this, "workerNodePolicy")
                                                                          .name("AmazonEKSWorkerNodePolicy")
                                                                          .build();
        final DataAwsIamPolicy cniPolicy = DataAwsIamPolicy.Builder.create(this, "cniPolicy")
                                                                   .name("AmazonEKS_CNI_Policy")
                                                                   .build();
        final DataAwsIamPolicy containerRegistryPolicy = DataAwsIamPolicy.Builder.create(this, "containerRegistryPolicy")
                                                                                 .name("AmazonEC2ContainerRegistryReadOnly")
                                                                                 .build();

        IamRolePolicyAttachment.Builder.create(this, "workerNodePolicyAttachment")
                                       .policyArn(workerNodePolicy.getArn())
                                       .role(nodeGroupRole.getName())
                                       .build();

        IamRolePolicyAttachment.Builder.create(this, "cniPolicyAttachment")
                                       .policyArn(cniPolicy.getArn())
                                       .role(nodeGroupRole.getName())
                                       .build();

        IamRolePolicyAttachment.Builder.create(this, "containerRegistryPolicyAttachment")
                                       .policyArn(containerRegistryPolicy.getArn())
                                       .role(nodeGroupRole.getName())
                                       .build();

        // --- 5. EKS Managed Node Group Creation ---

        final EksNodeGroupScalingConfig scalingConfig = EksNodeGroupScalingConfig.builder()
                                                                                 .desiredSize(1.0)
                                                                                 .minSize(1.0)
                                                                                 .maxSize(2.0)
                                                                                 .build();

        EksNodeGroup.Builder.create(this, "eksNodeGroup")
                            .clusterName(this.cluster.getName())
                            .nodeGroupName("default-ng")
                            .subnetIds(subnetIds)
                            .nodeRoleArn(nodeGroupRole.getArn())
                            .instanceTypes(List.of("t3.medium"))
                            .scalingConfig(scalingConfig)
                            // Ensure the node group waits for the cluster to be created
                            .dependsOn(List.of(this.cluster))
                            .tags(Map.of("Name", "cdktf-eks-nodegroup"))
                            .build();
    }
}

// Define the main Terraform Stack
public class EksClusterStack extends TerraformStack {
    public EksClusterStack(final Construct scope, final String id) {
        super(scope, id);

        // Hardcoded region for this example. Change 'us-east-1' as needed.
        final String awsRegion = "us-east-1";

        // Initialize the AWS Provider
        AwsProvider.Builder.create(this, "aws")
                           .region(awsRegion)
                           .build();

        // Instantiate the EKS Cluster Construct
        new EksClusterConstruct(this, "myEksCluster", awsRegion);
    }

    // Application entry point for running the CDKTF synthesis
    public static void main(String[] args) {
        final App app = new App();
        new EksClusterStack(app, "cdktf-eks-app");
        app.synth();
    }
}
