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
import com.hashicorp.cdktf.providers.kubernetes.ingress.*;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProvider;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import org.jetbrains.annotations.NotNull;
import software.constructs.Construct;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.infra.Defaults.AWS_REGION;
import static health.emrys.afon.infra.Defaults.CLUSTER_NAME;
import static java.lang.String.format;

public class AwsConstruct extends AbstractConstruct {
    public static final String                     VPC_NAME = "cdktf-eks-vpc";
    private final       DataAwsEksClusterAuth      eksAuthData;
    private final       EksCluster                 cluster;
    private final       List<ITerraformDependable> eksDependOn;
    final               Vpc                        vpc;

    AwsConstruct(final Construct scope, final String id) {
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
        vpc = Vpc.Builder.create(this, "eksVpc")
                         .cidrBlock("10.0.0.0/16")
                         .enableDnsSupport(true)
                         .enableDnsHostnames(true)
                         .provisioners(destroySGsAndVPC())
                         .tags(Map.of("Name", VPC_NAME)).build();

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

        RouteTableAssociation subnet1Association = RouteTableAssociation.Builder.create(this, "subnet1Association")
                                                                                .subnetId(publicSubnet1.getId())
                                                                                .routeTableId(publicRouteTable.getId()).build();

        RouteTableAssociation subnet2Association = RouteTableAssociation.Builder.create(this, "subnet2Association")
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
//                                    .provisioners(destroyELBs())
                                    .version("1.31")
                                    .dependsOn(List.of(eksClusterRole,
                                                       publicSubnet1, publicSubnet2, igw, eksClusterPolicy,
                                                       subnet2Association, subnet1Association))
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

        eksDependOn = List.of(newKubeConfig, cluster, eksAuthData);

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
                        .resolveConflictsOnCreate("OVERWRITE")
                        .build();

//        createHttpsIngress("arn:aws:acm:eu-west-1:418295715997:certificate/3c86beda-5db1-4f4b-8136-b86cc8d6d43f",
//                           "TODO",
//                           "polaris-lb",
//                           8181
//                           );
    }


// ... inside your Stack class ...

    public Ingress createHttpsIngress(
            final String certArn,
            final String hostName,
            final String serviceName,
            final int servicePort) {
        KubernetesProvider kubernetesProvider = configureK8s();
        // The key annotations for the AWS Load Balancer Controller (LBC)
        Map<String, String> albAnnotations = Map.of(
                // 1. Tell the LBC to create an Application Load Balancer (ALB)
                "kubernetes.io/ingress.class", "alb",

                // 2. CRITICAL: Specify the ARN of the ACM certificate
                "alb.ingress.kubernetes.io/certificate-arn", certArn,

                // 3. Configure the ALB to listen on both HTTP (80) and HTTPS (443)
                "alb.ingress.kubernetes.io/listen-ports", "[{\"HTTP\": 80}, {\"HTTPS\": 443}]",

                // 4. Force all HTTP traffic to redirect to HTTPS
                "alb.ingress.kubernetes.io/actions.ssl-redirect", "{\"Type\": \"redirect\", \"RedirectConfig\": { \"Protocol\": \"HTTPS\", \"Port\": \"443\", \"StatusCode\": \"HTTP_301\"}}",

                // 5. Specify that the traffic from the ALB to the EKS Pods is standard HTTP
                "alb.ingress.kubernetes.io/backend-protocol", "HTTP",

                // 6. Make the ALB internet-facing
                "alb.ingress.kubernetes.io/scheme", "internet-facing"
                                                   );

        // Create the Ingress
        IngressSpecRuleHttpPath https = IngressSpecRuleHttpPath
                .builder()
                .path("/")
//              .pathType("Prefix")
                .backend(IngressSpecRuleHttpPathBackend.builder()
                                                       .serviceName("ssl-redirect")
                                                       .servicePort("use-annotation")
                                                       // Reference the redirect action defined in the annotations
                                                       .build())
                .build();
        IngressSpecRuleHttpPathBackend pathBackend = IngressSpecRuleHttpPathBackend
                .builder().serviceName(serviceName).servicePort(servicePort + "").build();
        IngressSpecRuleHttp servicePortRule = IngressSpecRuleHttp
                .builder()
                .path(Collections.singletonList(IngressSpecRuleHttpPath.builder()
                                                                       .path("/")
//                                                                     .pathType("Prefix")
                                                                       .backend(pathBackend)
                                                                       .build()))
                .build();
        return Ingress.Builder.create(this, "polaris-https-ingress")
                              .provider(kubernetesProvider)
                              .metadata(IngressMetadata.builder()
                                                       .name("polaris-ingress")
                                                       .namespace("default") // Change if your service is in a different namespace
                                                       .annotations(albAnnotations)
                                                       .build())
                              .spec(IngressSpec.builder()
                                               .ingressClassName("alb") // Explicitly set the ingress class
                                               // Define the TLS configuration using the LBC's "ssl-redirect" action
                                               .rule(List.of(
                                                       // Rule 1: HTTP-to-HTTPS Redirect (Uses the annotation action)
                                                       IngressSpecRule.builder()
                                                                      .host(hostName)
                                                                      .http(IngressSpecRuleHttp.builder()
                                                                                               .path(Collections.singletonList(https))
                                                                                               .build())
                                                                      .build(),

                                                       // Rule 2: HTTPS Traffic to Service X
                                                       IngressSpecRule.builder()
                                                                      .host(hostName)
                                                                      .http(servicePortRule)
                                                                      .build()
                                                            )
                                                    )
                                               .build())
                              .build();
    }

    public void configureLoadBalancerDestroy(List<Service> loadBalancers) {
                /*
        Using the VPC ID this way can lead to:
│ Destroy-time provisioners and their connection configurations may only reference attributes of the related resource, via 'self', 'count.index', or 'each.key'.
│
│ References to other resources during the destroy phase can cause dependency cycles and interact poorly with create_before_destroy.
         */
        Resource.Builder.create(this, "cleanupELBs")
                        // Ensure this runs only after the K8s Services are destroyed by Terraform
                        .dependsOn(loadBalancers)
//                        .provisioners(destroyELBs())
                        .build();
        Resource.Builder.create(this, "cleanupSecurityGroups")
                        .dependsOn(loadBalancers)
//                        .provisioners(destroySGsAndVPC())
                        .build();
    }

    public static @NotNull List<LocalExecProvisioner> destroySGsAndVPC() {
        return List.of(
                LocalExecProvisioner.builder()
                                    .type("local-exec")
                                    .when("destroy")
                                    .command(
                                            format("""
                                                           %s
                                                           %s
                                                           """,
                                                   getVPCCommand(),
                                                   cleanSecurityGroupsCommand("$VPC"))
                                            )
                                    .build()
                      );
    }

    public static @NotNull List<LocalExecProvisioner> destroyELBs() {
        return List.of(
                LocalExecProvisioner.builder()
                                    .type("local-exec")
                                    .when("destroy")
                                    .command(
                                            cleanCommands()
                                            )
                                    .build()
                      );
    }

    private static @NotNull String cleanCommands() {
        return format("""
                              %s
                              """,
                      cleanELBsCommand()
                     );
    }

    private static @NotNull String cleanEksCommand() {
        return format("eksctl delete cluster --name %s --region %s", CLUSTER_NAME, AWS_REGION);
    }

    private static @NotNull String cleanELBsCommand() {
        return format("""
                              echo "Destroying ELBs"
                              for ELB in $(aws elb describe-load-balancers --region %s --query 'LoadBalancerDescriptions[*].LoadBalancerName' --o text) ; do { 
                                  echo $ELB ; 
                                  aws elb delete-load-balancer --load-balancer-name $ELB ; 
                              } done
                              """, AWS_REGION);
    }

    private static @NotNull String cleanNetworkInterfacesCommand(String vpcId) {
        return format("""
                              echo "Destroying network interfaces"
                              for ENI in $(aws ec2 describe-network-interfaces --filters Name=vpc-id,Values=%s --region %s --query 'NetworkInterfaces[*].NetworkInterfaceId' --output text) ; do { 
                                  echo $ENI ; 
                                  aws ec2 delete-network-interface --network-interface-id $ENI ; 
                              } done
                              """, vpcId, AWS_REGION);
    }

    private static @NotNull String cleanRoutesCommand(String vpcId) {
        return format("""
                              echo "Destroying routes"
                              for RT in $(aws ec2 describe-route-tables --filters Name=vpc-id,Values=%s --region %s --query 'RouteTables[*].RouteTableId' --output text) ; do { 
                                  echo $RT ; 
                                  aws ec2 delete-route-table --route-table-id $RT --region %s ; 
                              } done
                              """, vpcId, AWS_REGION, AWS_REGION);
    }

    private static @NotNull String cleanGatewaysCommand(String vpcId) {
        return format("""
                              echo "Destroying gateways"
                              for IG in $(aws ec2 describe-internet-gateways --filters Name=attachment.vpc-id,Values=%s --region %s --query 'InternetGateways[*].InternetGatewayId' --output text) ; do { 
                                echo $IG ; 
                                aws ec2 detach-internet-gateway --internet-gateway-id $IG --vpc-id %s --region %s ; 
                                aws ec2 delete-internet-gateway --internet-gateway-id $IG --region %s ; 
                              } done
                              """, vpcId, AWS_REGION, vpcId, AWS_REGION, AWS_REGION);
    }

    private static @NotNull String cleanSecurityGroupsCommand(String vpcId) {
        return format("""
                              echo "Destroying security-groups"
                              for SG_ID in $(aws ec2 describe-security-groups --filters Name=vpc-id,Values=%s --region %s --query 'SecurityGroups[*].[GroupId]' --output text --no-cli-pager); do { 
                                echo $SG_ID ;  
                                aws ec2 delete-security-group --group-id $SG_ID || true ; 
                              } done
                              """, vpcId, AWS_REGION);
    }

    private static @NotNull String cleanSubNetsCommand(String vpcId) {
        return format("""
                              echo "Destroying subnets"
                              for SN in $(aws ec2 describe-network-interfaces --filters Name=vpc-id,Values=%s --region %s --query 'NetworkInterfaces[*].[SubnetId]' --output text) ; do { 
                                  echo $SN ; 
                                  aws ec2 delete-subnet --subnet-id $SN --region $REGION ; 
                              } done
                              """, vpcId, AWS_REGION, AWS_REGION);
    }

    private static @NotNull String getDeleteVPCCommand() {
        return format("""
                              echo "Destroying vpc"
                              aws ec2 delete-vpc --vpc-id $VPC
                              
                              """);
    }

    private static @NotNull String getVPCCommand() {
        return format("""
                              export VPC=$(aws ec2 describe-vpcs   --query "Vpcs[?Tags[?Key=='Name' && Value=='%s']].VpcId"   --output text)
                              """, VPC_NAME);
    }

    @Override
    public List<ITerraformDependable> getDependables() {
        return eksDependOn; // List.of(cluster, eksAuthData);
    }

    @Override
    public String getBase64CertData() {
        return cluster.getCertificateAuthority().get(0).getData();
    }

    @Override
    public String getClusterEndpoint() {
        return cluster.getEndpoint();
    }

    @Override
    public String getToken() {
        return eksAuthData.getToken();
    }

    /**
     * Note that EKS depends on the subnets.
     * Once the EKS is nuked with "eksctl delete cluster", you can delete the subnets with:
     * aws ec2 delete-subnet --subnet-id subnet-XXX
     * then delete the whole VPC
     */
    public static void main(String[] args) {
        String vpcId = "$VPC";
        System.out.println(cleanCommands());
        System.out.println(format("""
                                          %s
                                          %s
                                          %s
                                          %s
                                          %s
                                          %s
                                          %s
                                          %s
                                          %s
                                          aws iam delete-role --role-name  cdktf-eks-cluster-role
                                          """,
                                  getVPCCommand(),
                                  cleanELBsCommand(),
                                  cleanGatewaysCommand(vpcId),
                                  cleanNetworkInterfacesCommand(vpcId),
                                  cleanSecurityGroupsCommand(vpcId),
                                  cleanRoutesCommand(vpcId),
                                  cleanEksCommand(),
                                  cleanSubNetsCommand(vpcId),
                                  getDeleteVPCCommand()
                                 ));
    }
}
