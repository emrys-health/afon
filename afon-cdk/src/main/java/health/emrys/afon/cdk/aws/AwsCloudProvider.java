package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.Aspects;
import com.hashicorp.cdktf.Fn;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.TerraformOutput;
import com.hashicorp.cdktf.TerraformResourceLifecycle;
import com.hashicorp.cdktf.providers.aws.data_aws_eip.DataAwsEip;
import com.hashicorp.cdktf.providers.aws.data_aws_eks_cluster.DataAwsEksCluster;
import com.hashicorp.cdktf.providers.aws.data_aws_eks_cluster.DataAwsEksClusterConfig;
import com.hashicorp.cdktf.providers.aws.data_aws_eks_cluster_auth.DataAwsEksClusterAuth;
import com.hashicorp.cdktf.providers.aws.data_aws_iam_policy.DataAwsIamPolicy;
import com.hashicorp.cdktf.providers.aws.data_aws_lb.DataAwsLb;
import com.hashicorp.cdktf.providers.aws.data_aws_lb.DataAwsLbConfig;
import com.hashicorp.cdktf.providers.aws.eip.Eip;
import com.hashicorp.cdktf.providers.aws.eks_access_entry.EksAccessEntry;
import com.hashicorp.cdktf.providers.aws.eks_access_entry.EksAccessEntryConfig;
import com.hashicorp.cdktf.providers.aws.eks_access_policy_association.EksAccessPolicyAssociation;
import com.hashicorp.cdktf.providers.aws.eks_access_policy_association.EksAccessPolicyAssociationAccessScope;
import com.hashicorp.cdktf.providers.aws.eks_access_policy_association.EksAccessPolicyAssociationConfig;
import com.hashicorp.cdktf.providers.aws.eks_cluster.EksCluster;
import com.hashicorp.cdktf.providers.aws.eks_cluster.EksClusterAccessConfig;
import com.hashicorp.cdktf.providers.aws.eks_cluster.EksClusterVpcConfig;
import com.hashicorp.cdktf.providers.aws.eks_node_group.EksNodeGroup;
import com.hashicorp.cdktf.providers.aws.eks_node_group.EksNodeGroupScalingConfig;
import com.hashicorp.cdktf.providers.aws.iam_openid_connect_provider.IamOpenidConnectProvider;
import com.hashicorp.cdktf.providers.aws.iam_openid_connect_provider.IamOpenidConnectProviderConfig;
import com.hashicorp.cdktf.providers.aws.iam_role.IamRole;
import com.hashicorp.cdktf.providers.aws.iam_role.IamRoleConfig;
import com.hashicorp.cdktf.providers.aws.iam_role.IamRoleInlinePolicy;
import com.hashicorp.cdktf.providers.aws.iam_role_policy_attachment.IamRolePolicyAttachment;
import com.hashicorp.cdktf.providers.aws.iam_role_policy_attachment.IamRolePolicyAttachmentConfig;
import com.hashicorp.cdktf.providers.aws.internet_gateway.InternetGateway;
import com.hashicorp.cdktf.providers.aws.nat_gateway.NatGateway;
import com.hashicorp.cdktf.providers.aws.provider.AwsProvider;
import com.hashicorp.cdktf.providers.aws.route53_record.Route53Record;
import com.hashicorp.cdktf.providers.aws.route53_record.Route53RecordAlias;
import com.hashicorp.cdktf.providers.aws.route53_record.Route53RecordConfig;
import com.hashicorp.cdktf.providers.aws.route_table.RouteTable;
import com.hashicorp.cdktf.providers.aws.route_table.RouteTableRoute;
import com.hashicorp.cdktf.providers.aws.route_table_association.RouteTableAssociation;
import com.hashicorp.cdktf.providers.aws.subnet.Subnet;
import com.hashicorp.cdktf.providers.aws.vpc.Vpc;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.provider.HelmProviderKubernetes;
import com.hashicorp.cdktf.providers.helm.provider.HelmProviderKubernetesExec;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.helm.release.ReleaseSet;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.*;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProvider;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProviderExec;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccount;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccountMetadata;
import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import health.emrys.afon.cdk.AbstractCloudProvider;
import health.emrys.afon.cdk.KubeConfigAspect;
import health.emrys.afon.cdk.HealthCheck;
import health.emrys.afon.cdk.HttpsExposure;
import health.emrys.afon.cdk.LogStorage;
import health.emrys.afon.cdk.HttpsRoute;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.config.StackId;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.constructs.Construct;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static health.emrys.afon.cdk.CdkHelper.*;
import static health.emrys.afon.cdk.CdkUtils.addMaps;
import static health.emrys.afon.cdk.CdkUtils.uponCreateRun;
import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.cdk.CdkUtils.vendoredChart;
import static health.emrys.afon.cdk.configs.SparkWorkloads.MASTER_LABELS;
import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.AfonProperty.AWS_ACCOUNT_ID;
import static health.emrys.afon.config.AfonProperty.AWS_EGRESS_IP;
import static java.lang.String.format;

public class AwsCloudProvider extends AbstractCloudProvider {

    private static final String LB_SERVICE_ACCOUNT_NAME = "aws-load-balancer-controller";

    private static final Logger log = LoggerFactory.getLogger(AwsCloudProvider.class);

    /**
     * Create timeout for a {@code kubernetes_ingress_v1}'s {@code wait_for_load_balancer} wait
     * (provider default is ~20m). A normal ALB provisions in a few minutes even the first time
     * in a fresh account (which pays a one-time cost to create the
     * AWSServiceRoleForElasticLoadBalancing service-linked role); anything past that usually
     * means the LB controller is stuck (crash, missing IAM permissions, bad subnet tags), not
     * just slow. Failing sooner lets StepActions' apply-retry get more attempts in the same
     * overall time budget. A plain constant for now; promote to an AfonProperty if it needs to
     * be tunable per environment.
     */
    static final String INGRESS_LOAD_BALANCER_TIMEOUT = "5m";

    private final AfonPropertyProvider       properties;
    private final AwsCleanupHelper           cleanupHelper;
    private final AwsComponentNames          componentNames;
    private final String                     accountId;
    private final String                     region;
    private final String                     clusterName;
    private final String                     kubeConfigPath;
    private final String                     clusterPolicyArn;
    private final EksCluster                 cluster;
    private final List<ITerraformDependable> eksDependOn;
    private final List<String>               subnetIds;
    private final List<String>               privateSubnetIds;
    private final String                     egressIp;
    final         DataAwsEksClusterAuth      eksAuthData;
    final         IamRole                    loadBalancerRole;
    final         Resource                   newKubeConfig;
    final         Release                    lbRelease;
    final         Vpc                        vpc;
    private final Resource                   lbcDestroyCleanup;
    private       HelmProvider               helmProvider;
    private final List<ITerraformDependable> lbcIrsaDependables = new ArrayList<>();
    private       KubernetesProvider         k8sProvider;

    /**
     * @param pinnedEgressIp whether the egress IP is pinned to {@code AWS_EGRESS_IP}, if set, else a fresh one is
     *                       allocated, as only one running stack can hold the pinned one
     */
    AwsCloudProvider(final Construct scope, final String id, AfonPropertyProvider properties, StackId stackId,
                     boolean pinnedEgressIp) {
        super(scope, id);
        this.properties = properties;
        componentNames = new AwsComponentNames(properties, stackId);
        cleanupHelper  = new AwsCleanupHelper(componentNames);
        clusterName    = componentNames.getCluster();
        kubeConfigPath = ConfigUtils.kubeConfigPath(stackId);

        this.accountId         = properties.getValue(AWS_ACCOUNT_ID);
        this.region            = properties.getValue(AfonProperty.AWS_REGION);
        clusterPolicyArn       = format("arn:aws:iam::%s:policy/AWSLoadBalancerControllerIAMPolicyCurl", accountId);
        // --- AWS Provider Configuration (Required for EKS Addon) ---
        // 1. Configure the AWS Provider
        AwsProvider.Builder.create(this, "aws")
                           .region(region) // Set your desired AWS Region
                           .build();


        vpc = Vpc.Builder
            .create(this, "eksVpc")
            .cidrBlock("10.0.0.0/16")
            .enableDnsSupport(true)
            .enableDnsHostnames(true)
            .provisioners(uponDestroyRun(cleanupHelper.deleteSecurityGroupsCommand()))
            .tags(Map.of("Name", componentNames.getVpcId())).build();

        final InternetGateway igw = InternetGateway.Builder
            .create(this, "vpcIgw")
            .vpcId(vpc.getId())
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .tags(Map.of("Name", componentNames.getInternetGatewayName())).build();

        final Subnet publicSubnet1 = Subnet.Builder
            .create(this, "publicSubnet1")
            .vpcId(vpc.getId())
            .cidrBlock("10.0.1.0/24")
            .availabilityZone(region + "a")
            .mapPublicIpOnLaunch(true)
            .dependsOn(List.of(igw))
            .provisioners(uponDestroyRun(cleanupHelper.deleteLoadBalancersCommand()))
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .tags(Map.of("Name", componentNames.getPublicSubnetName(1))).build();

        final Subnet publicSubnet2 = Subnet.Builder
            .create(this, "publicSubnet2")
            .vpcId(vpc.getId())
            .cidrBlock("10.0.2.0/24")
            .availabilityZone(region + "b")
            .mapPublicIpOnLaunch(true)
            .dependsOn(List.of(igw))
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .tags(Map.of("Name", componentNames.getPublicSubnetName(2))).build();

        final RouteTableRoute route = RouteTableRoute
            .builder()
            .cidrBlock("0.0.0.0/0")
            .gatewayId(igw.getId()).build();

        final RouteTable publicRouteTable = RouteTable.Builder
            .create(this, "publicRouteTable")
            .vpcId(vpc.getId())
            .route(List.of(route))
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .tags(Map.of(
                "Name",
                componentNames.getPublicRouteTableName())).build();

        final RouteTableAssociation subnet1Association = RouteTableAssociation.Builder
            .create(this, "subnet1Association")
            .subnetId(publicSubnet1.getId())
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .routeTableId(publicRouteTable.getId()).build();

        final RouteTableAssociation subnet2Association = RouteTableAssociation.Builder
            .create(this, "subnet2Association")
            .subnetId(publicSubnet2.getId())
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .routeTableId(publicRouteTable.getId()).build();

        subnetIds = List.of(publicSubnet1.getId(), publicSubnet2.getId());

        // --- 2b. Single-IP egress ---
        // Everything the cluster sends to the internet has to arrive from one address a
        // third party can put on an allow-list, so the nodes live in private subnets whose
        // default route is a NAT gateway holding a single Elastic IP.
        //
        // AWS_EGRESS_IP pins that address to an Elastic IP already allocated in this
        // account, which is what makes the address survive a destroy/apply cycle. Left
        // unset, a fresh EIP is allocated and reported as the `egressIp` output - note that
        // a `tofu destroy` then releases it, and the next apply gets a different address.
        // Only a stack pinning its egress IP (the Polaris stacks) uses AWS_EGRESS_IP, as an EIP can only be
        // attached to one NAT gateway, and so to one running stack: the others always get a fresh EIP.
        final String configuredEgressIp = pinnedEgressIp ? properties.getValue(AWS_EGRESS_IP, "").trim() : "";
        final String natAllocationId;
        if (configuredEgressIp.isEmpty()) {
            final Eip natEip = Eip.Builder
                .create(this, "natEip")
                .domain("vpc")
                // AWS documents that an EIP used by a VPC resource needs the internet
                // gateway in place first.
                .dependsOn(List.of(igw))
                .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                .tags(Map.of("Name", componentNames.getEgressIpName())).build();
            natAllocationId = natEip.getAllocationId();
            egressIp        = natEip.getPublicIp();
        } else {
            // The address is owned outside this stack: look it up rather than manage it, so
            // a destroy cannot release an IP that is still on somebody's allow-list.
            final DataAwsEip givenEip = DataAwsEip.Builder
                .create(this, "natEip")
                .publicIp(configuredEgressIp).build();
            // aws_eip's `id` is the allocation id; the data source exposes no separate one.
            natAllocationId = givenEip.getId();
            egressIp        = givenEip.getPublicIp();
        }

        final Subnet privateSubnet1 = Subnet.Builder
            .create(this, "privateSubnet1")
            .vpcId(vpc.getId())
            .cidrBlock("10.0.11.0/24")
            .availabilityZone(region + "a")
            .mapPublicIpOnLaunch(false)
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .tags(Map.of("Name", componentNames.getPrivateSubnetName(1))).build();

        final Subnet privateSubnet2 = Subnet.Builder
            .create(this, "privateSubnet2")
            .vpcId(vpc.getId())
            .cidrBlock("10.0.12.0/24")
            .availabilityZone(region + "b")
            .mapPublicIpOnLaunch(false)
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .tags(Map.of("Name", componentNames.getPrivateSubnetName(2))).build();

        // The NAT gateway itself must sit in a *public* subnet - it is the only thing in
        // the VPC that still talks to the internet gateway directly.
        final NatGateway natGateway = NatGateway.Builder
            .create(this, "natGateway")
            .allocationId(natAllocationId)
            .subnetId(publicSubnet1.getId())
            .dependsOn(List.of(igw, subnet1Association))
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .tags(Map.of("Name", componentNames.getNatGatewayName())).build();

        final RouteTableRoute natRoute = RouteTableRoute
            .builder()
            .cidrBlock("0.0.0.0/0")
            .natGatewayId(natGateway.getId()).build();

        final RouteTable privateRouteTable = RouteTable.Builder
            .create(this, "privateRouteTable")
            .vpcId(vpc.getId())
            .route(List.of(natRoute))
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .tags(Map.of("Name", componentNames.getPrivateRouteTableName())).build();

        final RouteTableAssociation privateSubnet1Association = RouteTableAssociation.Builder
            .create(this, "privateSubnet1Association")
            .subnetId(privateSubnet1.getId())
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .routeTableId(privateRouteTable.getId()).build();

        final RouteTableAssociation privateSubnet2Association = RouteTableAssociation.Builder
            .create(this, "privateSubnet2Association")
            .subnetId(privateSubnet2.getId())
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .routeTableId(privateRouteTable.getId()).build();

        privateSubnetIds = List.of(privateSubnet1.getId(), privateSubnet2.getId());

        // Reported so the address can be handed to whoever maintains the allow-list, and
        // fed back into AWS_EGRESS_IP to keep it across rebuilds.
        TerraformOutput.Builder
            .create(this, "egressIp")
            .value(egressIp)
            .description("Public IP all outbound cluster traffic is seen to come from")
            .build();

        // --- 3. EKS Cluster IAM Role (Same as before) ---

        final String eksAssumeRolePolicy = """
                                           {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"eks.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";
        final IamRole eksClusterRole = IamRole.Builder
            .create(this, "eksClusterRole")
            .name(componentNames.getClusterRoleName())
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .assumeRolePolicy(eksAssumeRolePolicy).build();

        final DataAwsIamPolicy eksClusterPolicy = DataAwsIamPolicy.Builder.create(this, "eksClusterPolicy")
                                                                          .name("AmazonEKSClusterPolicy").build();

        IamRolePolicyAttachment roleAttachment = IamRolePolicyAttachment.Builder
            .create(this, "eksClusterRoleAttachment")
            .policyArn(eksClusterPolicy.getArn())
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .provisioners(uponDestroyRun(
                format(
                    "aws iam detach-role-policy --role-name %s --policy-arn %s",
                    componentNames.getClusterRoleName(),
                    clusterPolicyArn),
                format(
                    "aws iam detach-role-policy --role-name %s --policy-arn " +
                        "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy",
                    componentNames.getClusterRoleName())))
            .role(eksClusterRole.getName()).build();

        // Control-plane ENIs are placed across both tiers; the public ones keep the
        // cluster endpoint reachable, the private ones sit alongside the nodes.
        final List<String> allSubnetIds = new ArrayList<>(subnetIds);
        allSubnetIds.addAll(privateSubnetIds);
        final EksClusterVpcConfig vpcConfig = EksClusterVpcConfig.builder().subnetIds(allSubnetIds).build();

        cluster = EksCluster.Builder
            .create(this, "eksCluster")
            .name(clusterName)
            .roleArn(eksClusterRole.getArn())
            .vpcConfig(vpcConfig)
            .version("1.35")
            .accessConfig(EksClusterAccessConfig.builder()
                                                .authenticationMode("API_AND_CONFIG_MAP")
                                                .bootstrapClusterCreatorAdminPermissions(true)
                                                .build())
            .provisioners(uponDestroyRun(NO_INSTALLATION_FINALIZERS))
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .dependsOn(List.of(roleAttachment))
            .tags(Map.of("Name", "cdktf-eks")).build();

        // Grant cluster-admin to every IAM principal in this AWS account so
        // anyone with EKS IAM permissions can administer (and tofu-destroy) the cluster,
        // not just the IAM identity that ran `tofu apply`.
        final String accountRootArn = format("arn:aws:iam::%s:root", accountId);
        final EksAccessEntry accountRootAccessEntry = new EksAccessEntry(
            this, "accountRootAccessEntry",
            EksAccessEntryConfig.builder()
                                .clusterName(cluster.getName())
                                .principalArn(accountRootArn)
                                .type("STANDARD")
                                .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                                .dependsOn(List.of(cluster))
                                .build());
        new EksAccessPolicyAssociation(
            this, "accountRootClusterAdmin",
            EksAccessPolicyAssociationConfig.builder()
                                            .clusterName(cluster.getName())
                                            .principalArn(accountRootArn)
                                            .policyArn("arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy")
                                            .accessScope(EksAccessPolicyAssociationAccessScope.builder()
                                                                                              .type("cluster")
                                                                                              .build())
                                            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                                            .dependsOn(List.of(accountRootAccessEntry))
                                            .build());

        // if one depends_on this, then the cluster should be running
        eksAuthData = DataAwsEksClusterAuth.Builder
            .create(this, "eksClusterAuthData")
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
        final IamRole nodeGroupRole = IamRole.Builder
            .create(this, "eksNodeGroupRole")
            .name(componentNames.getNodeGroupRoleName())
            .assumeRolePolicy(workerAssumeRolePolicy).build();

        // Required policy attachments
        final DataAwsIamPolicy workerNodePolicy = DataAwsIamPolicy.Builder
            .create(this, "workerNodePolicy")
            .name("AmazonEKSWorkerNodePolicy").build();
        final DataAwsIamPolicy cniPolicy = DataAwsIamPolicy.Builder
            .create(this, "cniPolicy")
            .name("AmazonEKS_CNI_Policy").build();
        final DataAwsIamPolicy containerRegistryPolicy = DataAwsIamPolicy.Builder
            .create(
                this,
                "containerRegistryPolicy")
            .name(
                "AmazonEC2ContainerRegistryReadOnly").build();

        final IamRolePolicyAttachment workerNodeAttachment = IamRolePolicyAttachment.Builder
            .create(this, "workerNodePolicyAttachment")
            .policyArn(workerNodePolicy.getArn())
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .role(nodeGroupRole.getName()).build();

        final IamRolePolicyAttachment cniAttachment = IamRolePolicyAttachment.Builder
            .create(this, "cniPolicyAttachment")
            .policyArn(cniPolicy.getArn())
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .role(nodeGroupRole.getName()).build();

        final IamRolePolicyAttachment containerRegistryAttachment = IamRolePolicyAttachment.Builder
            .create(this, "containerRegistryPolicyAttachment")
            .policyArn(containerRegistryPolicy.getArn())
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .role(nodeGroupRole.getName()).build();

        // --- 5. EKS Managed Node Group Creation (Same as before) ---

        final EksNodeGroupScalingConfig scalingConfig = EksNodeGroupScalingConfig
            .builder()
            .desiredSize(3.0)
            .minSize(3.0)
            .maxSize(3.0)
            .build();

        // Writes the stack's own kubeconfig, which its kubectl and helm commands use (see KubeConfigAspect), and
        // also updates the default kubeconfig, so that kubectl points at the newly deployed cluster, as for the
        // smoke tools.  Given explicitly, as the KUBECONFIG set by KubeConfigAspect would otherwise take over.
        List<LocalExecProvisioner> updateKubeConfig = List.of(
            LocalExecProvisioner
                .builder()
                .when("create") // Run only when the resource is created
                .command(format(
                             "mkdir -p %s && aws eks update-kubeconfig --name %s --region %s --kubeconfig %s" +
                                 " && aws eks update-kubeconfig --name %s --region %s --kubeconfig $HOME/.kube/config",
                             Path.of(kubeConfigPath).getParent(), clusterName, region, kubeConfigPath,
                             clusterName, region)
                        )
                .type("local-exec")
                .build()
                                                             );
        newKubeConfig = Resource.Builder.create(this, "kubeconfigUpdater")
                                        .dependsOn(List.of(cluster, eksAuthData)) // Wait for the cluster to be ready
                                        .provisioners(updateKubeConfig)
                                        .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                                        .build();
        EksNodeGroup nodeGroup = EksNodeGroup.Builder
            .create(this, "eksNodeGroup")
            .clusterName(cluster.getName())
            .nodeGroupName("default-ng")
            // Private subnets only: a node in a public subnet gets its own public IP and
            // would egress from that instead of the NAT gateway's single address.
            .subnetIds(privateSubnetIds)
            .nodeRoleArn(nodeGroupRole.getArn())
            .instanceTypes(List.of("r6i.large"))
            .scalingConfig(scalingConfig)
            .diskSize(50)
            // The nodes live in public subnets and reach the EKS API endpoint through the
            // route table's internet-gateway route, and need their IAM policies to operate.
            // Nothing else depends on those, so on destroy Terraform removes them in its
            // first wave, while pods and Helm releases are still terminating: the kubelets
            // lose the API server within a minute (nodes go NotReady), pods stick in
            // Terminating, and `helm uninstall` (which waits) times out with "failed to
            // delete release". Depending on them here keeps them until the node group,
            // and therefore every release/resource that depends on it, is gone.
            .dependsOn(List.of(
                newKubeConfig, cluster, eksAuthData,
                publicSubnet1, publicSubnet2,
                subnet1Association, subnet2Association,
                workerNodeAttachment,
                cniAttachment, containerRegistryAttachment,
                privateSubnet1Association, privateSubnet2Association))
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .tags(Map.of("Name", componentNames.getNodeGroupRoleName())).build();


        String clusterEndpoint = cluster.getEndpoint();
        String apiHost         = Fn.replace(clusterEndpoint, "https://", "");

        // Basically, EKS assumes you want a few components that you don't need if you use Cilium
        // vpc-cni addon removed: Cilium ENI mode is a complete replacement for vpc-cni.
        // Keeping the EKS addon caused EKS to continuously overwrite Cilium's no-op patch
        // to aws-node (resolveConflictsOnUpdate=OVERWRITE), breaking pod internet connectivity.
        List<LocalExecProvisioner> onCiliumCreation = uponCreateRun(
            "kubectl delete ds aws-node -n kube-system  || echo no aws-node",
            "kubectl delete ds kube-proxy -n kube-system || echo no kube-proxy",
            format(
                "aws eks delete-addon --cluster-name %s --addon-name vpc-cni || " +
                    "echo no vpc-cni", clusterName),
            format(
                "aws eks delete-addon --cluster-name %s --addon-name kube-proxy ||" +
                    " echo no kube-proxy", clusterName));
        // Both Cilium releases install from a chart vendored into the repo rather than
        // pulling from https://helm.cilium.io/ on every apply. See CdkUtils#vendoredChart.
        String ciliumChart = vendoredChart(this, "ciliumChart", "cilium-1.15.0.tgz");
        Release cilium = Release.Builder
            .create(this, "cilium")
            .name("cilium")
            .chart(ciliumChart)
            .namespace("kube-system")
            .dependsOn(List.of(nodeGroup))
            .provisioners(onCiliumCreation)
            // No version() — the vendored tarball is the version, and the provider reads it
            // from the chart's own Chart.yaml.
            // Set the specific values to kill kube-proxy dependency
            .set(java.util.List.of(
                // 1. Connectivity to EKS Control Plane
                createValue("kubeProxyReplacement", "true"),
                createValue("k8sServiceHost", apiHost),
                createValue("k8sServicePort", "443"),

                // 2. EKS-Specific Networking (ENI Mode)
                createValue("eni.enabled", "true"),
                createValue("ipam.mode", "eni"),
                createValue("routingMode", "native"),
                // BPF masquerade is required: kubeProxyReplacement uses bpf_redirect
                // to forward pod egress directly to the ENI, bypassing iptables
                // POSTROUTING — so iptables MASQUERADE never fires.
                createValue("bpf.masquerade", "true"),
                createValue("enableIPv4Masquerade", "true"),
                createValue("ipv4NativeRoutingCIDR", vpc.getCidrBlock()),
                // ENI IPAM picks the subnet for each pod ENI itself, preferring whichever
                // subnet in the node's AZ has the most free addresses. Unfiltered it can
                // land pods in a public subnet, where they have neither a public IP nor a
                // NAT route and so no egress at all - pin it to the private subnets.
                createValue("eni.subnetIDsFilter[0]", privateSubnetIds.get(0)),
                createValue("eni.subnetIDsFilter[1]", privateSubnetIds.get(1)),

                // FQDN-based network policy (L7 DNS): toEndpoints/toFQDNs rules need
                // the DNS proxy, which is gated by the cilium-config key
                // `enable-fqdn-policy`. The chart writes arbitrary ConfigMap keys via
                // the `extraConfig` map, so set it there.
                createStringValue("extraConfig.enable-fqdn-policy", "true"),

                // 3. Solving the "Pending" Issue (Tolerations)
                // This allows Cilium to run even if the node is "NotReady"
                createValue("tolerations[0].operator", "Exists"),
                createValue("tolerations[0].effect", "NoSchedule"),

                // Also apply to the operator so it can manage the pods
                createValue("operator.tolerations[0].operator", "Exists"),
                createValue("operator.tolerations[0].effect", "NoSchedule"),

                // 4. Prometheus metrics endpoints.
                // - cilium-agent: /metrics on container port 9962
                // - cilium-operator: /metrics on container port 9963
                // - cilium-envoy:   /metrics on container port 9964
                // The matching ServiceMonitor toggles (prometheus.serviceMonitor.enabled,
                // operator.prometheus.serviceMonitor.enabled) are intentionally LEFT OFF:
                // they need monitoring.coreos.com CRDs which kube-prometheus-stack
                // installs in a later component (MonitoringComponent). The ServiceMonitor
                // for Cilium is therefore created on the Prometheus side once those CRDs
                // exist.
                createValue("prometheus.enabled", "true"),
                createValue("operator.prometheus.enabled", "true"),

                // 5. Standalone cilium-envoy DaemonSet (L7 proxy). Default in 1.15 runs
                // envoy embedded in the cilium-agent container; enabling this splits it
                // out so agent restarts don't drop L7 connections. envoy.prometheus is
                // on by default in 1.15 — set explicitly for clarity.
                createValue("envoy.enabled", "true"),
                createValue("envoy.prometheus.enabled", "true"),
                // envoy is scheduled on every node like the agent, so it needs the same
                // permissive tolerations (otherwise it can't start on NotReady nodes).
                createValue("envoy.tolerations[0].operator", "Exists"),
                createValue("envoy.tolerations[0].effect", "NoSchedule"),

                // 6. Hubble — network observability (flow visibility + metrics).
                // - hubble.enabled: on-node flow collector inside cilium-agent
                // - hubble.relay: aggregates flows from all nodes (gRPC :4245)
                // - hubble.ui:    web UI for browsing flows; consumes relay
                // hubble-metrics Service is created automatically when at least one
                // metric category is enabled; it exposes /metrics on port 9965 from
                // each cilium-agent pod (port name `hubble-metrics`). The matching
                // ServiceMonitor is created on the Prometheus side, same reason as
                // the agent/operator/envoy ones above (CRD ordering).
                createValue("hubble.enabled", "true"),
                // Default cert issuer (auto.method=helm) bakes certs into the
                // helm release and has been observed to leave cilium-agent's
                // hubble TLS listener silent on EKS + kubeProxyReplacement,
                // so hubble-relay's dial to hubble-peer:443 hangs and the
                // startup probe trips. The cronJob issuer regenerates the
                // CA + serving certs from inside the cluster and keeps them
                // rotated, which clears the handshake stall.
                createValue("hubble.tls.auto.method", "cronJob"),
                createValue("hubble.relay.enabled", "true"),
                // Cold-start dials of hubble-peer can take longer than the
                // 5s default while BPF socket-LB warms up after a fresh
                // Cilium rollout — give the peer client enough headroom to
                // finish the handshake before the 5-min startup-probe budget
                // is consumed.
                createValue("hubble.relay.dialTimeout", "30s"),
                // hubble-relay's own /metrics endpoint (port 9966, name `metrics`)
                // exposed via the hubble-relay-metrics Service in kube-system.
                // ServiceMonitor for it is created on the Prometheus side, same
                // CRD-ordering reason as the agent/operator/envoy/hubble monitors.
                createValue("hubble.relay.prometheus.enabled", "true"),
                // hubble-ui is intentionally NOT enabled here. It ships as a
                // separate standalone release (see below) so Terraform can
                // order it after the datapath is Ready and its hardcoded,
                // no-grace frontend liveness probe doesn't trip on cold start.
                createValue("hubble.ui.enabled", "false"),
                createValue("hubble.metrics.enableOpenMetrics", "true"),
                createValue("hubble.metrics.enabled[0]", "dns"),
                createValue("hubble.metrics.enabled[1]", "drop"),
                createValue("hubble.metrics.enabled[2]", "tcp"),
                createValue("hubble.metrics.enabled[3]", "flow"),
                createValue("hubble.metrics.enabled[4]", "port-distribution"),
                createValue("hubble.metrics.enabled[5]", "icmp"),
                createValue("hubble.metrics.enabled[6]", "httpV2")
                                  ))
            .build();

        eksDependOn = List.of(newKubeConfig, cluster, eksAuthData, publicSubnet1, publicSubnet2, cilium);

        // Gate: block until the Cilium datapath is actually Ready (the main
        // release above is not atomic, so its completion does not imply the
        // agent DaemonSet has rolled out). The relay wait is best-effort — the
        // UI frontend probe only needs pod networking, not relay — but waiting
        // for it means the backend has something to talk to on first start.
        Resource ciliumReady = Resource.Builder
            .create(this, "ciliumDatapathReady")
            .dependsOn(List.of(cilium))
            .provisioners(uponCreateRun(
                "kubectl -n kube-system rollout status daemonset/cilium --timeout=300s",
                "kubectl -n kube-system rollout status deployment/hubble-relay --timeout=300s || true"
            ))
            .build();

        // hubble-ui as its own release, gated on ciliumReady. The frontend's
        // hardcoded liveness probe on :8081 has no startup grace, so when it
        // was bundled in the Cilium release it got killed into CrashLoopBackoff
        // while the agent was still programming the node. Starting it only
        // after the datapath is Ready means :8081 is reachable immediately and
        // the probe passes first time — no restart needed. agent/operator are
        // off so this release renders hubble-ui only; hubble TLS is off because
        // relay's server TLS is disabled (UI talks to hubble-relay:80 in
        // plaintext), so no client-cert volume is required.
        Release.Builder
            .create(this, "hubble-ui")
            .name("hubble-ui")
            .chart(ciliumChart)
            .namespace("kube-system")
            .dependsOn(List.of(ciliumReady))
            .set(java.util.List.of(
                createValue("agent", "false"),
                createValue("operator.enabled", "false"),
                createValue("hubble.enabled", "true"),
                createValue("hubble.tls.enabled", "false"),
                createValue("hubble.ui.enabled", "true"),
                createValue("hubble.ui.standalone.enabled", "true")
            ))
            .build();

        // metrics-server — serves the Kubernetes Metrics API (metrics.k8s.io)
        // that backs `kubectl top nodes` and `kubectl top pods`. EKS does not
        // install it by default, so without this release those commands fail
        // with "error: Metrics API not available". Gated on ciliumReady because
        // metrics-server scrapes every kubelet over the pod network, which only
        // works once the Cilium datapath has rolled out.
        Release metricsServer = Release.Builder
            .create(this, "metrics-server")
            .name("metrics-server")
            .chart(vendoredChart(this, "metricsServerChart", "metrics-server-3.12.2.tgz"))
            .namespace("kube-system")
            .dependsOn(List.of(ciliumReady))
            .timeout(300)
            .atomic(true)
            .set(List.of(
                // EKS kubelets serve their summary API with self-signed certs
                // that are not part of the cluster CA, so metrics-server cannot
                // verify them and the scrape fails with an x509 error. Skipping
                // verification of the kubelet serving cert is the standard EKS
                // workaround. (The chart's default args already prefer the node
                // InternalIP, which is what we want here.)
                createValue("args[0]", "--kubelet-insecure-tls")
            ))
            .build();

        // Confirm the Metrics API is actually serving before the stack reports
        // done. metrics-server needs a scrape cycle (~15-60s) after rollout
        // before `kubectl top nodes` returns data rather than
        // "metrics not available yet", so retry briefly. Uses the local
        // kubeconfig written by newKubeConfig, same as the ciliumReady gate.
        Resource.Builder
            .create(this, "metricsServerReady")
            .dependsOn(List.of(metricsServer))
            .provisioners(uponCreateRun(
                "kubectl -n kube-system rollout status deployment/metrics-server --timeout=300s",
                "for i in $(seq 1 30); do kubectl top nodes && break || " +
                    "{ echo 'waiting for node metrics...'; sleep 10; }; done"
            ))
            .build();

        String trustPolicy = irsaTrustPolicy("kube-system", "aws-load-balancer-controller");
        loadBalancerRole = new IamRole(
            scope, "AmazonEKSLoadBalancerControllerRole",
            IamRoleConfig
                .builder()
                .name(componentNames.getLoadBalancerRoleName())
                .assumeRolePolicy(trustPolicy)
                .build());
        IamRolePolicyAttachment.Builder
            .create(this, "EKS-Cleanup-Policy-Attachment")
            .policyArn(clusterPolicyArn)
            .role(eksClusterRole.getName())
            .build();
        lbRelease = configureIngress(scope, loadBalancerRole);

        // Destroy-time cleanup: must run before lbRelease is uninstalled so the LBC
        // webhook doesn't block Helm and TargetGroupBinding finalizers can be removed.
        // The explicit helm uninstall --no-hooks pre-empts Terraform's own Helm destroy,
        // which fails when webhooks/finalizers interfere. Helm treats "release not found" as success.
        // The controller is uninstalled first, so it is gone before the ingresses are deleted:
        // each ingress created by createHttpsIngress is added to this resource's dependencies.
        // Ingress finalizers are stripped only afterwards, as a running controller re-adds them.
        List<LocalExecProvisioner> onLbcDestroy = uponDestroyRun(
            "kubectl get validatingwebhookconfigurations -o name 2>/dev/null | grep aws-load-balancer" +
                " | xargs -r kubectl delete --ignore-not-found=true",
            "kubectl get mutatingwebhookconfigurations -o name 2>/dev/null | grep aws-load-balancer |" +
                " xargs -r kubectl delete --ignore-not-found=true",
            "helm uninstall aws-load-balancer-controller-chart -n kube-system --no-hooks 2>/dev/null " +
                "|| true",
            "kubectl delete secret sh.helm.release.v1.aws-load-balancer-controller-chart.v1 -n kube-system || true",
            "kubectl wait --for=delete pod -l app.kubernetes.io/name=aws-load-balancer-controller " +
                "-n kube-system --timeout=120s || true",
            "kubectl get targetgroupbindings -A --no-headers 2>/dev/null | awk '{print $1, $2}' | " +
                "while read NS NAME; do kubectl patch targetgroupbinding \"$NAME\" -n \"$NS\" -p " +
                "'{\"metadata\":{\"finalizers\":[]}}' --type=merge 2>/dev/null || true; done",
            "kubectl get ingress -A --no-headers 2>/dev/null | awk '{print $1, $2}' | " +
                "while read NS NAME; do kubectl patch ingress \"$NAME\" -n \"$NS\" -p " +
                "'{\"metadata\":{\"finalizers\":[]}}' --type=merge 2>/dev/null || true; done");
        lbcDestroyCleanup = Resource.Builder
            .create(this, "lbc-destroy-cleanup")
            .dependsOn(List.of(lbRelease))
            .provisioners(onLbcDestroy)
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .build();
    }

    /**
     * The configured allow-list plus the cluster's own egress IP: pods that call Polaris by its
     * public domain name leave through the NAT gateway and come back in through the ALB, so without
     * it they would be locked out too. Empty (no restriction) when nothing is configured.
     */
    public List<String> polarisInboundCidrs(String allowedCidrsCsv) {
        List<String> cidrs = Arrays.stream(allowedCidrsCsv.split(","))
                                   .map(String::trim)
                                   .filter(cidr -> !cidr.isEmpty())
                                   .collect(Collectors.toCollection(ArrayList::new));
        if (!cidrs.isEmpty()) {
            cidrs.add(egressIp + "/32");
        }
        return cidrs;
    }


    private static ReleaseSet createValue(String name, String value) {
        return ReleaseSet.builder()
                         .name(name)
                         .value(value)
                         .build();
    }

    /**
     * Like {@link #createValue} but forces Helm to treat the value as a string
     * (equivalent to {@code --set-string}). Needed for values that land verbatim in
     * a ConfigMap's {@code data}, whose entries must be strings: an unquoted {@code true}
     * renders as a YAML bool and Kubernetes then rejects the ConfigMap.
     */
    private static ReleaseSet createStringValue(String name, String value) {
        return ReleaseSet.builder()
                         .name(name)
                         .value(value)
                         .type("string")
                         .build();
    }

    /**
     * Creates a Route 53 Alias A record pointing {@code domainName} at the ALB the AWS Load
     * Balancer Controller provisions for the given Ingress. The ALB is located via an
     * {@code aws_lb} data source filtered on the controller's tags (rather than reading the
     * Ingress' status, which the kubernetes provider returns as null unreliably), and the
     * ALB's own DNS name and hosted zone are used for the alias. The record is only created once
     * the ALB is active, so it resolves as soon as it exists.
     *
     * @param constructId   unique CDKTF construct ID for this record
     * @param r53ZoneId     the Route 53 hosted zone ID that owns {@code domainName}
     * @param domainName    the domain to point at the ALB (e.g. {@code "emrysjupyter.click"})
     * @param release       Helm release the record should depend on
     * @param ingress       IngressV1 the data-source lookup depends on (so it resolves at apply time)
     * @param ingressK8sName the Ingress' Kubernetes metadata.name, used to match the ALB's
     *                       {@code ingress.k8s.aws/stack=default/<name>} tag
     * @return the alias record, so callers can make workloads that resolve {@code domainName}
     *         depend on it rather than racing its creation
     */
    public Route53Record configureDNS(String constructId, String r53ZoneId, String domainName,
                                      Release release, IngressV1 ingress, String ingressK8sName) {
        // We deliberately do NOT read the ALB hostname from the Ingress' status. The
        // hashicorp/kubernetes provider routinely returns a null status[0] for an
        // ALB-backed Ingress even when wait_for_load_balancer is set and the ALB is
        // actually up (see kubernetes-sigs/aws-load-balancer-controller#2768 and
        // hashicorp/terraform-provider-kubernetes#2679), which made the Route53 alias
        // below fail with "Attempt to index null value".
        //
        // The AWS Load Balancer Controller does provision the ALB; only the status
        // read-back is unreliable. So we query AWS directly for the ALB the controller
        // created, identified by the tags it stamps on it. depends_on(ingress) defers
        // this data read to apply time, after the Ingress (and thus the ALB) exists.
        DataAwsLb alb = new DataAwsLb(
            this, constructId + "Alb", DataAwsLbConfig
            .builder()
            .tags(Map.of(
                "elbv2.k8s.aws/cluster", clusterName,
                "ingress.k8s.aws/stack", "default/" + ingressK8sName,
                "ingress.k8s.aws/resource", "LoadBalancer"))
            .dependsOn(List.of(ingress))
            .build());
        // The ALB is found as soon as the controller creates it, but it stays in the
        // "provisioning" state for a few minutes, during which its DNS name does not resolve.
        // An alias record created in that window resolves to nothing, and resolvers (the
        // VPC's, CoreDNS) cache that negative answer for the zone's SOA minimum TTL, so in-cluster
        // clients such as oauth2-proxy keep failing to reach the domain for ~10 minutes after the
        // ALB is actually up. Creating the record only once the ALB is active avoids that.
        // The wait polls every 15s for up to 10 minutes, and fails the apply if the ALB never
        // becomes active, which applyWithRetry retries.
        Resource albActive = Resource.Builder
            .create(this, constructId + "AlbActive")
            .dependsOn(List.of(alb))
            .triggers(Map.of("albArn", alb.getArn()))
            .provisioners(uponCreateRun(
                format("aws elbv2 wait load-balancer-available --load-balancer-arns %s --region %s",
                       alb.getArn(), region)))
            .build();
        return new Route53Record(
            this, constructId, Route53RecordConfig
            .builder()
            .zoneId(r53ZoneId)
            .name(domainName)
            .type("A")
            .allowOverwrite(true)
            .dependsOn(List.of(release, albActive))
            .alias(Route53RecordAlias
                       .builder()
                       .name(alb.getDnsName())
                       .zoneId(alb.getZoneId())
                       .evaluateTargetHealth(true)
                       .build())
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .build());
    }

    /**
     * The trust policy of a role assumed via IRSA by the given service account, through the OIDC provider
     * {@link #associateOIDCProvider} creates.
     */
    private String irsaTrustPolicy(String namespace, String serviceAccount) {
        return format(
            """
            ${jsonencode({\"Version\": \"2012-10-17\", \"Statement\": [{\"Effect\": \"Allow\", \"Principal\": {\"Federated\": \"arn:aws:iam::%s:oidc-provider/${replace(data.aws_eks_cluster.EksClusterData.identity[0].oidc[0].issuer, \"https://\", \"\")}\"}, \"Action\": \"sts:AssumeRoleWithWebIdentity\", \"Condition\": {\"StringEquals\": {\"${replace(data.aws_eks_cluster.EksClusterData.identity[0].oidc[0].issuer, \"https://\", \"\")}:sub\": \"system:serviceaccount:%s:%s\"}}}]})}
            """, accountId, namespace, serviceAccount);
    }

    /**
     * Creates a role that the given service account assumes via IRSA, as the load balancer controller does.  Pods
     * cannot use the node role instead: the nodes require IMDSv2 with a hop limit of 1, so the instance metadata,
     * and with it the node role's credentials, is out of a pod's reach.
     *
     * @param policy the role's inline policy
     */
    public IamRole createIrsaRole(String id, String roleName, String namespace, String serviceAccount, String policy) {
        return IamRole.Builder
            .create(this, id)
            .name(roleName)
            .assumeRolePolicy(irsaTrustPolicy(namespace, serviceAccount))
            .inlinePolicy(List.of(IamRoleInlinePolicy.builder().name(roleName + "-policy").policy(policy).build()))
            // The OIDC provider must exist for the role to be assumed, and outlive the pods assuming it.
            .dependsOn(lbcIrsaDependables)
            .build();
    }

    private Release configureIngress(Construct scope, IamRole loadBalancerRole) {
        ServiceAccount serviceAccount = configureServiceAccount(scope, loadBalancerRole);

        return helmInstallLoadBalancer(serviceAccount, loadBalancerRole);
    }

    private @NonNull ServiceAccount configureServiceAccount(Construct scope, IamRole loadBalancerRole) {
        DataAwsEksCluster clusterData        = associateOIDCProvider(scope);
        String            serviceAccountName = LB_SERVICE_ACCOUNT_NAME;

        lbcIrsaDependables.add(new IamRolePolicyAttachment(
            scope,
            "LBCPolicyAttachment",
            IamRolePolicyAttachmentConfig
                .builder()
                .dependsOn(List.of(loadBalancerRole))
                .role(loadBalancerRole.getName())
                .policyArn(clusterPolicyArn)
                .build()));


        Map<String, String> labels = Map.of(
            "app.kubernetes.io/component", "controller",
            "app.kubernetes.io/instance", "aws-load-balancer" +
                "-controller",
            "app.kubernetes.io/name", "aws-load-balancer" +
                "-controller"
                                           );
        Map<String, String> annotations = Map.of(
            "eks.amazonaws.com/role-arn",
            loadBalancerRole.getArn()
                                                );
        return ServiceAccount.Builder
            .create(scope, serviceAccountName)
            .metadata(ServiceAccountMetadata
                          .builder()
                          .name(serviceAccountName)
                          .namespace("kube-system")
                          .labels(labels)
                          .annotations(annotations)
                          .build())
            .provisioners(uponDestroyRun(
                NO_INSTALLATION_FINALIZERS,
                NO_JUPYTER_LB_FINALIZERS,
                NO_POLARIS_FINALIZERS,
                NO_JUPYTER_FINALIZERS,
                NO_KEYCLOAK_FINALIZERS))
            .build();
    }

    private @NonNull DataAwsEksCluster associateOIDCProvider(Construct scope) {
        // 1. Fetch the EKS Cluster details to get the OIDC Issuer URL
        DataAwsEksCluster clusterData = new DataAwsEksCluster(
            scope, "EksClusterData",
            DataAwsEksClusterConfig.
                builder()
                .dependsOn(eksDependOn)
                .name(clusterName)
                .build());

        // Extract the OIDC Issuer URL from the cluster data
        // The issuer is in the format: https://oidc.eks.region.amazonaws.com/id/EXAMPLED9295F773010C7C5F77A986877E...
        String oidcIssuerUrl = clusterData.getIdentity().get(0).getOidc().get(0).getIssuer();

        // 2. Create the IAM OIDC Provider resource
        IamOpenidConnectProviderConfig providerConfig = IamOpenidConnectProviderConfig
            .builder()
            // The URL for the provider. Must be retrieved from the EKS cluster data.
            .url(oidcIssuerUrl)

            // This is the OIDC audience claim, required to allow Kubernetes Service
            // Accounts to exchange their tokens for AWS IAM roles (IRSA).
            .clientIdList(List.of("sts.amazonaws.com"))

            // The thumbprint of the OIDC provider's root certificate.
            // For EKS OIDC, the required thumbprint is well-known and consistent
            // across AWS regions, simplifying the setup compared to custom providers.
            // It's recommended to hardcode this known thumbprint for EKS:
//                .thumbprintList(List.of("9e99a48a9960b14926cc7f3b02e22da2b0e791e8"))

            // Optional: Tag the resource
            .tags(Map.of("Name", componentNames.getOidcProviderName()))
            .build();
        lbcIrsaDependables.add(new IamOpenidConnectProvider(scope, "EksIamOidcProvider", providerConfig));
        return clusterData;
    }

    private Release helmInstallLoadBalancer(ServiceAccount serviceAccount, IamRole loadBalancerRole) {
        String serviceAccountName = LB_SERVICE_ACCOUNT_NAME;
        ArrayList<ITerraformDependable> dependencies = new ArrayList(eksDependOn);
        dependencies.add(loadBalancerRole);
        // The chart doesn't create the service account, it's only referenced by name, so without this
        // edge it may be destroyed first: the controller's token is then Unauthorized, it cannot remove
        // its finalizers, and the ingresses stick in Terminating.
        dependencies.add(serviceAccount);
        // The controller assumes loadBalancerRole via IRSA (OIDC provider + policy attachment) to
        // delete its ALBs when it is uninstalled. Without these edges they are destroyed first,
        // the controller loses its AWS permissions, and the ingresses stick in Terminating.
        dependencies.addAll(lbcIrsaDependables);
        return Release.Builder
            .create(this, "aws-load-balancer")
            .dependsOn(dependencies)
            .name(serviceAccountName + "-chart")
            .chart(vendoredChart(this, "loadBalancerChart", "aws-load-balancer-controller-1.17.0.tgz"))
            .namespace("kube-system")
            .timeout(600)
            .atomic(true)
            .set(List.of(
                ReleaseSet.builder().name("clusterName").value(clusterName).build(),
                ReleaseSet.builder().name("serviceAccount.create").value("false").build(),
                ReleaseSet.builder().name("vpcId").value(vpc.getId()).build(),
                ReleaseSet.builder().name("serviceAccount.name").value(serviceAccountName).build()
                        ))
            .build();
    }

    /**
     * Sets up what every AWS stack needs: the null provider, the provider with its Helm provider, the stack's own
     * kubeconfig for its kubectl and helm commands, which only ever act on its own cluster, and the pinned provider
     * versions.
     *
     * @param pinnedEgressIp whether the stack's egress IP is pinned to {@code AWS_EGRESS_IP}, if set, which only
     *                       one running stack can hold, else the stack allocates its own
     */
    public static AwsCloudProvider bootstrap(TerraformStack stack,
                                             StackId stackId,
                                             AfonPropertyProvider properties,
                                             boolean pinnedEgressIp) {
        NullProvider.Builder.create(stack, "nullProvider")
                            .build();
        AwsCloudProvider cloudProvider = new AwsCloudProvider(
            stack, stackId.cloud().toString(), properties, stackId, pinnedEgressIp);
        cloudProvider.configureHelm();
        Aspects.of(stack).add(new KubeConfigAspect(ConfigUtils.kubeConfigPath(stackId)));
        pinProviderVersions(stack);
        return cloudProvider;
    }

    /**
     * Pins the exact versions of the providers every AWS stack uses.
     */
    private static void pinProviderVersions(TerraformStack stack) {
        stack.addOverride(
            "terraform.required_providers.aws", Map.of(
                "source", "hashicorp/aws",
                "version", "6.27.0"   // 👈 Pin exact version
                                                      ));
        stack.addOverride(
            "terraform.required_providers.helm", Map.of(
                "source", "hashicorp/helm",
                "version", "3.2.0"   // 👈 Pin exact version
                                                       ));
        stack.addOverride(
            "terraform.required_providers.kubernetes", Map.of(
                "source", "hashicorp/kubernetes",
                // 3.2.1 fixes "Unexpected Identity Change" errors when a resource takes
                // extended time to become ready (as happened when an EKS token expired
                // mid-rollout of oauth2-proxy). Do not downgrade below 3.2.1.
                "version", "3.2.1"   // 👈 Pin exact version
                                                             ));
    }

    @Override
    public AfonCloud cloud() {
        return AWS;
    }

    /**
     * @return the cluster name qualified by the stack type, eg {@code full-emrys-cluster}
     */
    @Override
    public String clusterName() {
        return componentNames.getCluster();
    }

    /**
     * The logs are kept in the bucket of the "aws" catalog (see catalogs-aws.properties), in the region, with the access
     * key of {@code AWS_ACCESS_KEY_ID} and {@code AWS_SECRET_ACCESS_KEY}.
     */
    @Override
    public LogStorage logStorage() {
        String bucket = properties.getCatalogProperty("AWS_BUCKET_NAME", "aws");
        return new LogStorage("S3", "s3", String.format("""
            type: s3
            bucketNames:
              chunks: %1$s
              ruler: %1$s
              admin: %1$s
            s3:
              region: %2$s
              endpoint: ""
              accessKeyId: %3$s
              secretAccessKey: %4$s
              s3ForcePathStyle: false
              insecure: false
            """, bucket, region, properties.getValue(AfonProperty.AWS_ACCESS_KEY_ID),
                                                       properties.getValue(AfonProperty.AWS_SECRET_ACCESS_KEY)));
    }

    /**
     * The ALB targets instances, which needs a node port on each node.
     */
    @Override
    public boolean routesToNodePorts() {
        return true;
    }

    @Override
    public void exposeSparkMaster(List<ITerraformDependable> eksDependOn) {
        String subnetIds = getSubnetIds().stream().collect(Collectors.joining(","));
        ArrayList<ITerraformDependable> lbDependencies = new ArrayList<>(eksDependOn);
        lbDependencies.add(loadBalancerRole);
        // Creating this LoadBalancer Service fires the AWS LB Controller's
        // mutating webhook (mservice.elbv2.k8s.aws). Depend on the controller's
        // Helm release — which is installed atomically, so Terraform waits for
        // its pods to be Ready — otherwise the webhook Service can still have
        // zero endpoints and the apply fails with "no endpoints available for
        // service aws-load-balancer-webhook-service".
        lbDependencies.add(getLbRelease());
        Service.Builder.create(stack(), SPARK_SERVICE_NAME)
                      .metadata(ServiceMetadata.builder()
                                               .name(SPARK_SERVICE_NAME)
                                               .labels(addMaps(MASTER_LABELS, Map.of(
                                                              "spark-version", "4.0.1",
                                                              "spark.operator/name", "spark-kubernetes-operator",
                                                              "spark.operator/spark-cluster-name", "prod"
                                                             )))
                                               .annotations(Map.of(
                                                       "service.beta.kubernetes.io/aws-load-balancer-subnets", subnetIds,
                                                       "service.beta.kubernetes.io/aws-load-balancer-internal", "false"))
                                               .build())
                      .dependsOn(lbDependencies)
                      .provisioners(uponDestroyRun(NO_SPARK_LB_FINALIZERS))
                      .spec(ServiceSpec.builder()
                                       .selector(MASTER_LABELS)
                                       .type("LoadBalancer")
                                       // The load balancer controller's webhook sets the class of every LoadBalancer
                                       // service. If the plan leaves it out, the provider sees it removed from the
                                       // live service, and replaces the service, and its load balancer, on each apply.
                                       .loadBalancerClass(LOAD_BALANCER_CLASS)
                                       .port(List.of(
                                               ServiceSpecPort.builder()
                                                              .name("web")
                                                              .port(8080) // Port the load balancer listens on
                                                              .targetPort("web") // Port the container is listening on
                                                              .protocol("TCP")
                                                              .build(),
                                               ServiceSpecPort.builder()
                                                              .name("spark")
                                                              .port(7077) // Port the load balancer listens on
                                                              .targetPort("7077") // Port the container is listening on
                                                              .protocol("TCP")
                                                              .build(),
                                               ServiceSpecPort.builder()
                                                              .name("rest")
                                                              .port(6066) // Port the load balancer listens on
                                                              .targetPort("rest") // Port the container is listening on
                                                              .protocol("TCP")
                                                              .build()
                                                    ))
                                       .build())
                              .build();
    }


    /**
     * Exposes the component behind an ALB, whose ACM certificate, Route 53 zone and, optionally, allow-list of source
     * CIDRs are configured by {@code AWS_<COMPONENT>_CERT_ARN}, {@code AWS_<COMPONENT>_DNS_ZONE} and
     * {@code AWS_<COMPONENT>_ALLOWED_CIDRS}.  The ALB terminates TLS, so the route's TLS secret is not used, and the
     * destroy-time finalizers of the ingress are removed by the provider.
     */
    @Override
    public HttpsExposure exposeHttps(HttpsRoute route) {
        String component = route.component().toUpperCase();
        // the construct ids are in the tofu state of the deployed stacks, so cannot be named alike
        String[] constructIds = switch (route.component()) {
            case "polaris" -> new String[]{"polaris-https-ingress", "IngressAliasRecord"};
            case "jupyter" -> new String[]{"jupyterhub-https-ingress", "JupyterHubAliasRecord"};
            case "keycloak" -> new String[]{"keycloak-https-ingress", "KeyCloakAliasRecord"};
            default -> new String[]{route.component() + "-https-ingress", route.component() + "AliasRecord"};
        };
        IngressV1 ingress = createHttpsIngress(
            constructIds[0],
            route.ingressName(),
            properties.getCloudValue(AWS, component + "_CERT_ARN"),
            route.domainName(),
            route.service(),
            route.port(),
            route.healthCheck(),
            getSubnetIds(),
            polarisInboundCidrs(properties.getCloudValue(AWS, component + "_ALLOWED_CIDRS", "")));
        Route53Record dnsRecord = configureDNS(
            constructIds[1],
            properties.getCloudValue(AWS, component + "_DNS_ZONE"),
            route.domainName(),
            getLbRelease(),
            ingress,
            route.ingressName());
        return new HttpsExposure(ingress, dnsRecord);
    }

    /** The class the AWS load balancer controller gives the LoadBalancer services it provisions an NLB for. */
    private static final String LOAD_BALANCER_CLASS = "service.k8s.aws/nlb";

    /** Restricts an ALB to the given comma-separated source CIDRs; absent means open to all. */
    public static final String INBOUND_CIDRS_ANNOTATION = "alb.ingress.kubernetes.io/inbound-cidrs";

    public IngressV1 createHttpsIngress(
        final String constructId,
        final String ingressName,
        final String certArn,
        final String hostName,
        final String serviceName,
        final int servicePort,
        final HealthCheck healthCheck,
        final List<String> subnetIds) {
        return createHttpsIngress(constructId, ingressName, certArn, hostName, serviceName, servicePort,
                                  healthCheck, subnetIds, List.of());
    }

    /**
     * As above but, when {@code inboundCidrs} is non-empty, the ALB's security group only admits
     * those source ranges. The IP allow-list has to live here rather than in a NetworkPolicy: the
     * ALB terminates the client's connection and opens its own, so pods only ever see the ALB's
     * (or a node's) VPC address, never the caller's.
     */
    public IngressV1 createHttpsIngress(
        final String constructId,
        final String ingressName,
        final String certArn,
        final String hostName,
        final String serviceName,
        final int servicePort,
        final HealthCheck healthCheck,
        final List<String> subnetIds,
        final List<String> inboundCidrs) {
        KubernetesProvider kubernetesProvider = configureK8s();
        // The key annotations for the AWS Load Balancer Controller (LBC)
        String subnetIdsCSV = subnetIds.stream().collect(Collectors.joining(","));
        log.info("subnetIDs = {}, certArn = {}, hostname = {}", subnetIdsCSV, certArn, hostName);
        Map<String, String> albAnnotations = new HashMap<>(Map.of(
            // 1. Tell the LBC to create an Application Load Balancer (ALB)
            "kubernetes.io/ingress.class", "alb",

            // 2. CRITICAL: Specify the ARN of the ACM certificate
            "alb.ingress.kubernetes.io/certificate-arn", certArn,

            // 3. Configure the ALB to listen on both HTTP (80) and HTTPS (443)
            "alb.ingress.kubernetes.io/listen-ports", "[{\"HTTP\": 80}, {\"HTTPS\": 443}]",

            // 4. Force all HTTP traffic to redirect to HTTPS
            "alb.ingress.kubernetes.io/actions.ssl-redirect", "443",

            // 5. Specify that the traffic from the ALB to the EKS Pods is standard HTTP
            "alb.ingress.kubernetes.io/backend-protocol", "HTTP",

            // 6. Make the ALB internet-facing
            "alb.ingress.kubernetes.io/scheme", "internet-facing",
            "external-dns.alpha.kubernetes.io/hostname", hostName,

            "alb.ingress.kubernetes.io/subnets", subnetIdsCSV
                                                   ));
        albAnnotations.put("alb.ingress.kubernetes.io/healthcheck-path", healthCheck.path());
        albAnnotations.put("alb.ingress.kubernetes.io/healthcheck-port", healthCheck.port());
        if (!inboundCidrs.isEmpty()) {
            albAnnotations.put(INBOUND_CIDRS_ANNOTATION, String.join(",", inboundCidrs));
        }

        var sslServicePort = IngressV1SpecRuleHttpPathBackendService
            .builder()
            .name(serviceName)
            .port(IngressV1SpecRuleHttpPathBackendServicePort
                      .builder()
                      .number(servicePort)
                      .build()
                 ).build();
        IngressV1SpecRuleHttpPathBackend pathBackend = IngressV1SpecRuleHttpPathBackend
            .builder().service(sslServicePort).build();
        IngressV1SpecRuleHttpPath pathRule = IngressV1SpecRuleHttpPath
            .builder()
            .path("/")
            .pathType("Prefix")
            .backend(pathBackend)
            .build();
        IngressV1SpecRuleHttp servicePortRule = IngressV1SpecRuleHttp
            .builder()
            .path(Collections.singletonList(pathRule))
            .build();
        IngressV1SpecRule ingressRule = IngressV1SpecRule
            .builder()
            .host(hostName)
            .http(servicePortRule)
            .build();
        IngressV1Spec alb = IngressV1Spec
            .builder()
            .ingressClassName("alb")
            .rule(List.of(ingressRule))
            .build();
        IngressV1Metadata ingressMetadata = IngressV1Metadata
            .builder()
            .name(ingressName)
            .namespace("default")
            .annotations(albAnnotations)
            .build();
        IngressV1 ingress = IngressV1.Builder
            .create(this, constructId)
            .provider(kubernetesProvider)
            // The controller normally removes its own `ingress.k8s.aws/resources` finalizer
            // when it sees the deletion, but by this point in the destroy it has been
            // uninstalled (see lbc-destroy-cleanup), so nothing would ever remove it and the
            // provider polls its delete until it times out. Terraform runs a resource's
            // destroy provisioners immediately before destroying it, so this strips the
            // finalizer whatever order the ingresses and the controller come apart in.
            .provisioners(uponDestroyRun(finalizeIngress(ingressName, "default")))
            // The ALB (and thus the ingress' status.loadBalancer.hostname that the Route53
            // alias record reads) is only provisioned once the AWS Load Balancer Controller
            // is running. Without this dependency the ingress can be created before the
            // controller's Helm release, so waitForLoadBalancer never sees a hostname and
            // status stays null, breaking the Route53 alias record.
            .dependsOn(List.of(lbRelease))
            .metadata(ingressMetadata)
            .spec(alb)
            .lifecycle(TerraformResourceLifecycle
                           .builder()
                           .preventDestroy(false)
                           .build())
            .waitForLoadBalancer(true)
            .timeouts(IngressV1Timeouts.builder().create(INGRESS_LOAD_BALANCER_TIMEOUT).build())
            .build();
        destroyAfterLbcCleanup(ingress);
        return ingress;
    }

    /**
     * Makes the lbc-destroy-cleanup depend on the ingress, so that on destroy the cleanup, which
     * uninstalls the AWS Load Balancer Controller and strips the ingress finalizers, runs before
     * the ingress is deleted. Done for every ingress {@link #createHttpsIngress} creates, so they
     * are all destroyed in the same order.
     */
    private void destroyAfterLbcCleanup(IngressV1 ingress) {
        List<String> dependsOn = new ArrayList<>(lbcDestroyCleanup.getDependsOn());
        // a plain reference, as the builder's dependsOn writes, not getFqn()'s "${...}" token
        dependsOn.add(ingress.getTerraformResourceType() + "." + ingress.getFriendlyUniqueId());
        lbcDestroyCleanup.setDependsOn(dependsOn);
    }

    public Release getLbRelease() {
        return lbRelease;
    }

    /**
     * Every AWS stack ends up with {@code helm_release} resources (cilium, hubble-ui,
     * metrics-server and the LB controller release above are all created in this
     * constructor), so a {@code helm} provider is required in every stack that builds an
     * {@link AwsCloudProvider} — not just the ones that install further Helm charts of
     * their own. Without it, Terraform falls back to an unconfigured default provider that
     * tries the local kubeconfig, which fails against a freshly created EKS cluster.
     * Mirrors {@code configureK8s()}'s memoization so repeated calls are safe.
     */
    public synchronized HelmProvider configureHelm() {
        if (helmProvider == null) {
            HelmProviderKubernetes kubernetes = HelmProviderKubernetes
                .builder()
                .host(getClusterEndpoint())
                .clusterCaCertificate(Fn.base64decode(getBase64CertData()))
                .exec(eksTokenExecForHelm())
                .build();
            helmProvider = HelmProvider.Builder
                .create(this, "helm")
                .kubernetes(kubernetes)
                .build();
        }
        return helmProvider;
    }

    /**
     * Uses {@code aws eks get-token} instead of the static token from {@code eksAuthData} so
     * the provider fetches a fresh, short-lived credential on every operation. The static
     * token is read once during the apply graph and expires after
     * ~15 minutes; a full-create apply of a brand-new cluster (EKS control plane + node group
     * + Helm releases) routinely takes longer than that, so late resources such as
     * {@code kubernetes_ingress_v1} would fail with "Unauthorized" once it went stale.
     */
    @Override
    public synchronized KubernetesProvider configureK8s() {
        if (k8sProvider == null) {
            k8sProvider = KubernetesProvider.Builder
                .create(this, "kubernetes")
                .host(getClusterEndpoint())
                .clusterCaCertificate(Fn.base64decode(getBase64CertData()))
                .exec(List.of(KubernetesProviderExec
                                  .builder()
                                  .apiVersion("client.authentication.k8s.io/v1beta1")
                                  .command("aws")
                                  .args(eksTokenArgs())
                                  .build()))
                .build();
        }
        return k8sProvider;
    }

    private HelmProviderKubernetesExec eksTokenExecForHelm() {
        return HelmProviderKubernetesExec
            .builder()
            .apiVersion("client.authentication.k8s.io/v1beta1")
            .command("aws")
            .args(eksTokenArgs())
            .build();
    }

    /**
     * @return the names of the AWS components of the stack
     */
    public AwsComponentNames getComponentNames() {
        return componentNames;
    }

    private List<String> eksTokenArgs() {
        return List.of("eks", "get-token", "--cluster-name", clusterName, "--region", region);
    }

    @Override
    public List<ITerraformDependable> getServiceDependables() {
        // The Keycloak Service is NodePort/LB-annotated, so its creation triggers the
        // aws-load-balancer-controller's mservice mutating webhook (failurePolicy=Fail). Wait on
        // the controller's Helm release (atomic, so Helm blocks until pods are Ready) to avoid the
        // "no endpoints available for service aws-load-balancer-webhook-service" race.
        return lbRelease == null ? List.of() : List.of(lbRelease);
    }

    /**
     * The public subnets. These are for internet-facing load balancers only - workloads
     * run in {@link #getPrivateSubnetIds()} so that their egress goes via the NAT gateway.
     */
    public List<String> getSubnetIds() {
        return new ArrayList<>(subnetIds);
    }

    /**
     * The subnets the nodes run in, whose default route is the NAT gateway holding the
     * single egress IP.
     */
    public List<String> getPrivateSubnetIds() {
        return new ArrayList<>(privateSubnetIds);
    }

    /** Public IP that all outbound traffic from the cluster appears to come from. */
    public String getEgressIp() {
        return egressIp;
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

    public String getAccountId() {
        return accountId;
    }

    public String getRegion() {
        return region;
    }
}
