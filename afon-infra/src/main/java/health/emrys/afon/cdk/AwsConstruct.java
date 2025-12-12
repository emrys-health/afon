package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.TerraformResourceLifecycle;
import com.hashicorp.cdktf.providers.aws.data_aws_eks_cluster.DataAwsEksCluster;
import com.hashicorp.cdktf.providers.aws.data_aws_eks_cluster.DataAwsEksClusterConfig;
import com.hashicorp.cdktf.providers.aws.data_aws_eks_cluster_auth.DataAwsEksClusterAuth;
import com.hashicorp.cdktf.providers.aws.data_aws_iam_policy.DataAwsIamPolicy;
import com.hashicorp.cdktf.providers.aws.eks_addon.EksAddon;
import com.hashicorp.cdktf.providers.aws.eks_cluster.EksCluster;
import com.hashicorp.cdktf.providers.aws.eks_cluster.EksClusterVpcConfig;
import com.hashicorp.cdktf.providers.aws.eks_node_group.EksNodeGroup;
import com.hashicorp.cdktf.providers.aws.eks_node_group.EksNodeGroupScalingConfig;
import com.hashicorp.cdktf.providers.aws.iam_openid_connect_provider.IamOpenidConnectProvider;
import com.hashicorp.cdktf.providers.aws.iam_openid_connect_provider.IamOpenidConnectProviderConfig;
import com.hashicorp.cdktf.providers.aws.iam_policy.IamPolicy;
import com.hashicorp.cdktf.providers.aws.iam_role.IamRole;
import com.hashicorp.cdktf.providers.aws.iam_role.IamRoleConfig;
import com.hashicorp.cdktf.providers.aws.iam_role_policy_attachment.IamRolePolicyAttachment;
import com.hashicorp.cdktf.providers.aws.iam_role_policy_attachment.IamRolePolicyAttachmentConfig;
import com.hashicorp.cdktf.providers.aws.internet_gateway.InternetGateway;
import com.hashicorp.cdktf.providers.aws.provider.AwsProvider;
import com.hashicorp.cdktf.providers.aws.route53_record.Route53Record;
import com.hashicorp.cdktf.providers.aws.route53_record.Route53RecordAlias;
import com.hashicorp.cdktf.providers.aws.route53_record.Route53RecordConfig;
import com.hashicorp.cdktf.providers.aws.route_table.RouteTable;
import com.hashicorp.cdktf.providers.aws.route_table.RouteTableRoute;
import com.hashicorp.cdktf.providers.aws.route_table_association.RouteTableAssociation;
import com.hashicorp.cdktf.providers.aws.secretsmanager_secret.SecretsmanagerSecret;
import com.hashicorp.cdktf.providers.aws.secretsmanager_secret_version.SecretsmanagerSecretVersion;
import com.hashicorp.cdktf.providers.aws.subnet.Subnet;
import com.hashicorp.cdktf.providers.aws.vpc.Vpc;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.helm.release.ReleaseSet;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.*;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProvider;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccount;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccountMetadata;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import org.jetbrains.annotations.NotNull;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static health.emrys.afon.cdk.AwsKubernetesPolaris.POLARIS_INTERNAL;
import static health.emrys.afon.cdk.AwsWholeStack.NO_INSTALLATION_FINALIZERS;
import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.cdk.PolarisConfig.NO_POLARIS_FINALIZERS;
import static health.emrys.afon.cdk.SparkClusterDeployment.NO_SPARK_LB_FINALIZERS;
import static health.emrys.afon.infra.Defaults.AWS_REGION;
import static health.emrys.afon.infra.Defaults.CLUSTER_NAME;
import static java.lang.String.format;

public class AwsConstruct extends AbstractConstruct {
    public static final String                     VPC_NAME           = "cdktf-eks-vpc";
    public static final String                     CLUSTER_POLICY_ARN = "arn:aws:iam::123456789012:policy/AWSLoadBalancerControllerIAMPolicyCurl"; // TODO remove 'Curl'
    final static        String                     NO_JUPYTER_LB      = "kubectl patch service jupyter-lb -p '{\"metadata\":{\"finalizers\":null}}'  --type=merge";
    final               DataAwsEksClusterAuth      eksAuthData;
    private final       EksCluster                 cluster;
    private final       List<ITerraformDependable> eksDependOn;
    private final       List<String>               subnetIds;
    final               Vpc                        vpc;
    final               IamRole                    loadBalancerRole;
    final               Resource                   newKubeConfig;

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

        // TODO Vpc needs to depend on the security groups
        vpc = Vpc.Builder.create(this, "eksVpc")
                         .cidrBlock("10.0.0.0/16")
                         .enableDnsSupport(true)
                         .enableDnsHostnames(true)
                         .provisioners(destroySGsAndVPC())
                         .tags(Map.of("Name", VPC_NAME)).build();

        final InternetGateway igw = InternetGateway.Builder.create(this, "vpcIgw")
                                                           .vpcId(vpc.getId())
                                                           .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                                                           .tags(Map.of("Name", "cdktf-eks-igw")).build();

        // TODO subnets need to depend on the load balancer
        final Subnet publicSubnet1 = Subnet.Builder.create(this, "publicSubnet1")
                                                   .vpcId(vpc.getId())
                                                   .cidrBlock("10.0.1.0/24")
                                                   .availabilityZone(AWS_REGION + "a")
                                                   .mapPublicIpOnLaunch(true)
                                                   .dependsOn(List.of(igw))
                                                   .provisioners(uponDestroyRun(cleanELBs()))
                                                   .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                                                   .tags(Map.of("Name", "cdktf-eks-public-1")).build();

        final Subnet publicSubnet2 = Subnet.Builder.create(this, "publicSubnet2")
                                                   .vpcId(vpc.getId())
                                                   .cidrBlock("10.0.2.0/24")
                                                   .availabilityZone(AWS_REGION + "b")
                                                   .mapPublicIpOnLaunch(true)
                                                   .dependsOn(List.of(igw))
                                                   .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                                                   .tags(Map.of("Name", "cdktf-eks-public-2")).build();

        final RouteTableRoute route = RouteTableRoute.builder()
                                                     .cidrBlock("0.0.0.0/0")
                                                     .gatewayId(igw.getId()).build();

        final RouteTable publicRouteTable = RouteTable.Builder.create(this, "publicRouteTable")
                                                              .vpcId(vpc.getId())
                                                              .route(List.of(route))
                                                              .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                                                              .tags(Map.of("Name", "cdktf-eks-public-rt")).build();

        RouteTableAssociation subnet1Association = RouteTableAssociation.Builder.create(this, "subnet1Association")
                                                                                .subnetId(publicSubnet1.getId())
                                                                                .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                                                                                .routeTableId(publicRouteTable.getId()).build();

        RouteTableAssociation subnet2Association = RouteTableAssociation.Builder.create(this, "subnet2Association")
                                                                                .subnetId(publicSubnet2.getId())
                                                                                .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                                                                                .routeTableId(publicRouteTable.getId()).build();

        subnetIds = List.of(publicSubnet1.getId(), publicSubnet2.getId());

        // --- 3. EKS Cluster IAM Role (Same as before) ---

        final String eksAssumeRolePolicy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"eks.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";
        final IamRole eksClusterRole = IamRole.Builder.create(this, "eksClusterRole")
                                                      .name("cdktf-eks-cluster-role")
                                                      .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                                                      .assumeRolePolicy(eksAssumeRolePolicy).build();

        final DataAwsIamPolicy eksClusterPolicy = DataAwsIamPolicy.Builder.create(this, "eksClusterPolicy")
                                                                          .name("AmazonEKSClusterPolicy").build();

        IamRolePolicyAttachment roleAttachment = IamRolePolicyAttachment
                .Builder.create(this, "eksClusterRoleAttachment")
                        .policyArn(eksClusterPolicy.getArn())
                        .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
//                                        .dependsOn(List.of(eksClusterRole))
//                                       .provisioners(uponDestroyRun("eksctl delete cluster --name spark-cluster --region eu-west-2"))
                        .provisioners(uponDestroyRun(
                                "aws iam detach-role-policy --role-name cdktf-eks-cluster-role --policy-arn arn:aws:iam::123456789012:policy/AWSLoadBalancerControllerIAMPolicyCurl",
                                "aws iam detach-role-policy --role-name cdktf-eks-cluster-role --policy-arn arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"))
                        .role(eksClusterRole.getName()).build();

        final EksClusterVpcConfig vpcConfig = EksClusterVpcConfig.builder().subnetIds(subnetIds).build();

        // this appears to be necessary to release Calico on destroy:
        // kubectl --kubeconfig ~/.kube/config  patch installation default -p '{"metadata":{"finalizers":[]}}' --type=merge
        cluster = EksCluster.Builder.create(this, "eksCluster")
                                    .name(CLUSTER_NAME)
                                    .roleArn(eksClusterRole.getArn())
                                    .vpcConfig(vpcConfig)
//                                    .provisioners(destroyELBs())
                                    .version("1.31")
                                    .provisioners(uponDestroyRun(NO_INSTALLATION_FINALIZERS))
                                    .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                                    .dependsOn(List.of(roleAttachment))
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
//                                                     .provisioners(uponDestroyRun("eksctl delete cluster --name spark-cluster --region eu-west-2"))
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
                                       .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
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
                                       .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                                       .role(nodeGroupRole.getName()).build();

        IamRolePolicyAttachment.Builder.create(this, "cniPolicyAttachment")
                                       .policyArn(cniPolicy.getArn())
                                       .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                                       .role(nodeGroupRole.getName()).build();

        IamRolePolicyAttachment.Builder.create(this, "containerRegistryPolicyAttachment")
                                       .policyArn(containerRegistryPolicy.getArn())
                                       .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                                       .role(nodeGroupRole.getName()).build();

        // --- 5. EKS Managed Node Group Creation (Same as before) ---

        final EksNodeGroupScalingConfig scalingConfig = EksNodeGroupScalingConfig.builder()
                                                                                 .desiredSize(3.0)
                                                                                 .minSize(3.0)
                                                                                 .maxSize(3.0)
                                                                                 .build();

        newKubeConfig = Resource.Builder.create(this, "kubeconfigUpdater")
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
                                        .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                                        .build();

        eksDependOn = List.of(newKubeConfig, cluster, eksAuthData, publicSubnet1, publicSubnet2);

        EksNodeGroup.Builder.create(this, "eksNodeGroup")
                            .clusterName(cluster.getName())
                            .nodeGroupName("default-ng")
                            .subnetIds(subnetIds)
                            .nodeRoleArn(nodeGroupRole.getArn())
                            .instanceTypes(List.of("t3.medium"))
                            .scalingConfig(scalingConfig)
                            .dependsOn(eksDependOn)
                            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
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
                        .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                        .build();

        String trustPolicy = """
                ${jsonencode({\"Version\": \"2012-10-17\", \"Statement\": [{\"Effect\": \"Allow\", \"Principal\": {\"Federated\": \"arn:aws:iam::123456789012:oidc-provider/${replace(data.aws_eks_cluster.EksClusterData.identity[0].oidc[0].issuer, \"https://\", \"\")}\"}, \"Action\": \"sts:AssumeRoleWithWebIdentity\", \"Condition\": {\"StringEquals\": {\"${replace(data.aws_eks_cluster.EksClusterData.identity[0].oidc[0].issuer, \"https://\", \"\")}:sub\": \"system:serviceaccount:kube-system:aws-load-balancer-controller\"}}}]})}
                """;
        loadBalancerRole = new IamRole(scope, "AmazonEKSLoadBalancerControllerRole",
                                       IamRoleConfig.builder()
                                                    .name("AmazonEKSLoadBalancerControllerRole")
                                                    .assumeRolePolicy(trustPolicy)
                                                    .build());
        IamRolePolicyAttachment.Builder.create(this, "EKS-Cleanup-Policy-Attachment")
                                       .policyArn(CLUSTER_POLICY_ARN)
                                       .role(eksClusterRole.getName())
                                       .build();
        Release release = configureIngress(scope, loadBalancerRole);

        IngressV1 ingress = createHttpsIngress("arn:aws:acm:eu-west-2:123456789012:certificate/7eaf973f-36d1-4f09-8481-751b3f85efa4",
                                               "emryspolaris.click",
                                               POLARIS_INTERNAL,
                                               8181,
                                               subnetIds
                                              );

//        final DataAwsRoute53Zone hostedZone = DataAwsRoute53Zone
//                .Builder.create(this, "HostedZoneLookup")
//                        .name("emryspolaris.click.") // Must end with a period
//                        .privateZone(false).count(6)
//                        .build();


        // 2. Create the Alias A Record
        // An Alias record is used to point a domain name at an AWS resource (like an ELB/ALB).
        // For the name, we use the specific subdomain (e.g., "app.mydomain.com").
        configureDNS(release, ingress);
    }

    private void configureDNS(Release release, IngressV1 ingress) {
        new Route53Record(this, "IngressAliasRecord", Route53RecordConfig
                .builder()
                .zoneId("Z06847223IC61YVGEH6HR")
                .name("emryspolaris.click")
                .type("A") // Use 'A' for an Alias to the ingress hostname
                .dependsOn(List.of(release))
                .alias(Route53RecordAlias.builder()
                                         .name(ingress.getStatus().get(0).getLoadBalancer().get(0).getIngress().get(0).getHostname())           // The load balancer hostname from your Ingress
                                         .zoneId("ZHURV8PSTC4K8") // hard coded that maps to eu-west-2
                                         .evaluateTargetHealth(true)
                                         .build())
                .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                .build());
    }

    private Release configureIngress(Construct scope, IamRole loadBalancerRole) {
        String serviceAccountName = configureServiceAccount(scope, loadBalancerRole);

        return helmInstallLoadBalancer(serviceAccountName, loadBalancerRole);
    }

    private @NotNull String configureServiceAccount(Construct scope, IamRole loadBalancerRole) {
        DataAwsEksCluster clusterData        = associateOIDCProvider(scope);
        String            serviceAccountName = "aws-load-balancer-controller";

        new IamRolePolicyAttachment(scope,
                                    "LBCPolicyAttachment",
                                    IamRolePolicyAttachmentConfig
                                            .builder()
                                            .dependsOn(List.of(loadBalancerRole))
                                            .role(loadBalancerRole.getName())
                                            .policyArn(CLUSTER_POLICY_ARN)
                                            .build());


        ServiceAccount.Builder.create(scope, serviceAccountName)
                              .metadata(ServiceAccountMetadata.builder()
                                                              .name(serviceAccountName)
                                                              .namespace("kube-system")
                                                              .labels(Map.of( // the eksctl has also: app.kubernetes.io/managed-by=eksctl
                                                                              "app.kubernetes.io/component", "controller",
                                                                              "app.kubernetes.io/instance", "aws-load-balancer-controller",
                                                                              "app.kubernetes.io/name", "aws-load-balancer-controller"
                                                                            ))
                                                              .annotations(Map.of(
                                                                      "eks.amazonaws.com/role-arn", loadBalancerRole.getArn()
                                                                                 ))
                                                              .build())
                              .provisioners(uponDestroyRun(NO_INSTALLATION_FINALIZERS,
//                                                           NO_SPARK_LB_FINALIZERS,
                                                           NO_JUPYTER_LB,
                                                           NO_POLARIS_FINALIZERS))
                              .build();
        return serviceAccountName;
    }

    private @NotNull DataAwsEksCluster associateOIDCProvider(Construct scope) {
        // 1. Fetch the EKS Cluster details to get the OIDC Issuer URL
        DataAwsEksCluster clusterData = new DataAwsEksCluster(scope, "EksClusterData",
                                                              DataAwsEksClusterConfig.
                                                                      builder()
                                                                      .dependsOn(eksDependOn)
                                                                      .name(CLUSTER_NAME)
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
                .tags(Map.of("Name", CLUSTER_NAME + "-oidc-provider"))
                .build();
        new IamOpenidConnectProvider(scope, "EksIamOidcProvider", providerConfig);
        return clusterData;
    }

    private Release helmInstallLoadBalancer(String serviceAccountName, IamRole loadBalancerRole) {
        ArrayList<ITerraformDependable> dependencies = new ArrayList(eksDependOn);
        dependencies.add(loadBalancerRole);
        return Release.Builder.create(this, "aws-load-balancer")
                              .dependsOn(dependencies)
                              .name(serviceAccountName + "-chart")
                              .chart("aws-load-balancer-controller")
                              .repository("https://aws.github.io/eks-charts")
                              .namespace("kube-system")
                              .timeout(600)
                              .atomic(true)
                              .set(List.of(
                                      ReleaseSet.builder().name("clusterName").value(CLUSTER_NAME).build(),
                                      ReleaseSet.builder().name("serviceAccount.create").value("false").build(),
                                      ReleaseSet.builder().name("vpcId").value(vpc.getId()).build(),
                                      ReleaseSet.builder().name("serviceAccount.name").value(serviceAccountName).build()
                                          ))
                              .build();
    }

    public IngressV1 createHttpsIngress(
            final String certArn,
            final String hostName,
            final String serviceName,
            final int servicePort,
            final List<String> subnetIds) {
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
                "alb.ingress.kubernetes.io/actions.ssl-redirect", "443",

                // 5. Specify that the traffic from the ALB to the EKS Pods is standard HTTP
                "alb.ingress.kubernetes.io/backend-protocol", "HTTP",

                // 6. Make the ALB internet-facing
                "alb.ingress.kubernetes.io/scheme", "internet-facing",
                "external-dns.alpha.kubernetes.io/hostname", hostName,

                "alb.ingress.kubernetes.io/subnets", subnetIds.stream().collect(Collectors.joining(","))
                                                   );

        // Create the Ingress
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
        IngressV1SpecRuleHttp servicePortRule = IngressV1SpecRuleHttp
                .builder()
                .path(Collections.singletonList(IngressV1SpecRuleHttpPath.builder()
                                                                         .path("/")
                                                                         .pathType("Prefix")
                                                                         .backend(pathBackend)
                                                                         .build()))
                .build();
        // kubectl patch ingress polaris-ingress -p '{"metadata":{"finalizers":[]}}' --type=merge
        IngressV1 ingress = IngressV1.Builder.create(this, "polaris-https-ingress")
                                             .provider(kubernetesProvider)
                                             .metadata(IngressV1Metadata.builder()
                                                                        .name("polaris-ingress")
                                                                        .namespace("default") // Change if your service is in a different namespace
                                                                        .annotations(albAnnotations)
                                                                        .build())
                                             .spec(IngressV1Spec.builder()
                                                                .ingressClassName("alb") // Explicitly set the ingress class
                                                                // Define the TLS configuration using the LBC's "ssl-redirect" action
                                                                .rule(List.of(
                                                                              // Rule 1: HTTP-to-HTTPS Redirect (Uses the annotation action)
//                                                       IngressV1SpecRule.builder()
//                                                                      .host(hostName)
//                                                                      .http(IngressV1SpecRuleHttp.builder()
//                                                                                               .path(Collections.singletonList(https))
//                                                                                               .build())
//                                                                      .build(),

                                                                              // Rule 2: HTTPS Traffic to Service X
                                                                              IngressV1SpecRule.builder()
                                                                                               .host(hostName)
                                                                                               .http(servicePortRule)
                                                                                               .build()
                                                                             )
                                                                     )
                                                                .build())
                                             .lifecycle(TerraformResourceLifecycle
                                                                .builder()
                                                                .preventDestroy(false)
//                                                   .ignoreChanges("metadata.0.finalizers")
                                                                // Explicitly prevents the destroy from being blocked by changes in the status
                                                                // (which is where the finalizer status is reflected in the eyes of Terraform).
//                                                   .ignoreChanges(List.of("status"))
                                                                .build())
                                             .waitForLoadBalancer(true)
                                             .build();
//        ingress.addOverride("metadata.finalizers", Collections.emptyList());
        // (Optional) Ignore future controller-added finalizers so deletes never block
//        ingress.addOverride("lifecycle.ignore_changes", Collections.singletonList("metadata.0.finalizers"));
        return ingress;
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
                        .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                        .build();
        Resource.Builder.create(this, "cleanupSecurityGroups")
                        .dependsOn(loadBalancers)
                        .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                        .build();
    }

    public static @NotNull List<LocalExecProvisioner> destroySGsAndVPC() {
        return uponDestroyRun(format("""
                                             %s
                                             %s
                                             """,
                                     getVPCCommand(),
                                     cleanSecurityGroupsCommand("$VPC")));
    }

    private static @NotNull String cleanSecurityGroupsCommand(String vpcId) {
        return format("""
                              echo "Destroying security-groups"
                              for SG_ID in $(aws ec2 describe-security-groups --filters Name=vpc-id,Values=%s --region %s --query 'SecurityGroups[*].[GroupId]' --output text --no-cli-pager); do { 
                                echo $SG_ID ;  
                                aws ec2 delete-security-group --group-id $SG_ID || true 
                              } done""", vpcId, AWS_REGION);
    }

    private static @NotNull String cleanELBs() {
        return format("""
                              %s
                              for LB in $(aws elbv2 describe-load-balancers  --query "LoadBalancers[?VpcId=='$VPC'].LoadBalancerArn" --output text) ; do {
                                 echo $LB
                                 aws elbv2 delete-load-balancer  --load-balancer-arn $LB
                               } done
                              """,
                      getVPCCommand());
    }

    private static @NotNull String getVPCCommand() {
        return format("export VPC=$(%s)", getVPCsCommand());
    }

    private static @NotNull String getVPCsCommand() {
        return format("""
                              aws ec2 describe-vpcs --query "Vpcs[?Tags[?Key=='Name' && Value=='%s']].VpcId"   --output text""",
                      VPC_NAME);
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

    public List<String> getSubnetIds() {
        return new ArrayList<>(subnetIds);
    }

    private static @NotNull String cleanEksCommand() {
        return format("eksctl delete cluster --name %s --region %s", CLUSTER_NAME, AWS_REGION);
    }

    /**
     * Note that EKS depends on the subnets.
     * Once the EKS is nuked with "eksctl delete cluster", you can delete the subnets with:
     * aws ec2 delete-subnet --subnet-id subnet-XXX
     * then delete the whole VPC
     */
    public static void main(String[] args) {
        System.out.println(format("""
                                          \u001B[31m
                                          for VPC_ID in $(%s) ; do {
                                             echo Destroying $VPC_ID
                                             echo destroying service and ingress...
                                              kubectl patch service jupyter-lb      -p '{"metadata":{"finalizers":null}}'  --type=merge
                                              kubectl patch ingress polaris-ingress -p '{"metadata":{"finalizers":null}}'  --type=merge
                                              %s
                                              for LB in $(aws elbv2 describe-load-balancers  --query "LoadBalancers[?VpcId=='$VPC_ID'].LoadBalancerArn" --output text) ; do {
                                                echo $LB
                                                aws elbv2 delete-load-balancer  --load-balancer-arn $LB
                                              } done
                                              echo destroying internet gateways...
                                              for igw in $(aws ec2 describe-internet-gateways --filters Name=attachment.vpc-id,Values=$VPC_ID --query 'InternetGateways[].InternetGatewayId' --output text); do { 
                                                echo $igw
                                                aws ec2 detach-internet-gateway --internet-gateway-id $igw --vpc-id $VPC_ID; 
                                                aws ec2 delete-internet-gateway --internet-gateway-id $igw; 
                                              } done; 
                                              echo VPC endpoints...
                                              aws ec2 describe-vpc-endpoints --filters Name=vpc-id,Values=$VPC_ID --query 'VpcEndpoints[].VpcEndpointId' --output text | \\ 
                                                xargs -r aws ec2 delete-vpc-endpoints --vpc-endpoint-ids; 
                                              echo NAT gateways...
                                              aws ec2 describe-nat-gateways --filter Name=vpc-id,Values=$VPC_ID --query 'NatGateways[].NatGatewayId' --output text | \\
                                                xargs -r -n1 aws ec2 delete-nat-gateway --nat-gateway-id; 
                                              echo network interfaces...
                                              aws ec2 describe-network-interfaces --filters Name=vpc-id,Values=$VPC_ID --query 'NetworkInterfaces[].NetworkInterfaceId' --output text | \\ 
                                                xargs -r -n1 aws ec2 delete-network-interface --network-interface-id;
                                              echo ELBs
                                              for SUBNET_ID in $(aws ec2 describe-subnets --filters Name=vpc-id,Values=$VPC_ID  --query 'Subnets[].{Id:SubnetId}' --output text) ; do { 
                                                for ELB in $(aws elb describe-load-balancers --query 'LoadBalancerDescriptions[?Subnets.contains(@, `'"$SUBNET_ID"'`)].LoadBalancerName' --output text) ; do {
                                                  echo $ELB
                                                  aws elb delete-load-balancer --load-balancer-name  $ELB
                                                } done
                                              } done   
                                              echo subnets...
                                              for subnet in $(aws ec2 describe-subnets --filters Name=vpc-id,Values=$VPC_ID --query 'Subnets[].SubnetId' --output text); do {
                                                echo $SUBNET_ID ; 
                                                aws ec2 delete-subnet --subnet-id $subnet; 
                                              } done; 
                                              echo routes tables...
                                              for rtb in $(aws ec2 describe-route-tables --filters Name=vpc-id,Values=$VPC_ID --query 'RouteTables[].RouteTableId' --output text); do {
                                                for ASSOC in $(aws ec2 describe-route-tables --route-table-ids $rtb --query "RouteTables[0].Associations[0].RouteTableAssociationId" --output text); do {
                                                  echo $rdb $ASSOC
                                                  aws ec2 disassociate-route-table   --association-id $ASSOC ;
                                                } done
                                                if ! aws ec2 describe-route-tables --route-table-ids $rtb --query 'RouteTables[].Associations[?Main].Main' --output text | grep -q True; then
                                                  echo $rdb 
                                                  aws ec2 delete-route-table --route-table-id $rtb; 
                                                fi; 
                                              } done; 
                                              echo security groups...
                                              for sg in $(aws ec2 describe-security-groups --filters Name=vpc-id,Values=$VPC_ID --query 'SecurityGroups[?GroupName!=`default`].GroupId' --output text); do {
                                                echo $sg 
                                                aws ec2 delete-security-group --group-id $sg; 
                                              } done; 
                                              echo network ACLs
                                              for nacl in $(aws ec2 describe-network-acls --filters Name=vpc-id,Values=$VPC_ID --query 'NetworkAcls[?IsDefault==`false`].NetworkAclId' --output text); do {
                                                echo $nacl 
                                                aws ec2 delete-network-acl --network-acl-id $nacl; 
                                              } done; 
                                              echo and the VPC
                                              aws ec2 delete-vpc --vpc-id $VPC_ID
                                          } done
                                          
                                          for ROLE_NAME in $(echo "cdktf-eks-cluster-role" "cdktf-eks-nodegroup-role" "AmazonEKSLoadBalancerControllerRole") ; do {
                                             echo $ROLE_NAME ;
                                             for ARN in $(aws iam list-attached-role-policies --role-name $ROLE_NAME --query 'AttachedPolicies[*].PolicyArn' --output text) ; do {
                                                 aws iam detach-role-policy --role-name $ROLE_NAME --policy-arn $ARN
                                             } done
                                             for PROFILE in $(aws iam list-instance-profiles-for-role --query 'AttachedPolicies[*].PolicyArn' --role-name $ROLE_NAME --query "InstanceProfiles[].InstanceProfileName"   --output text) ; do {
                                                 aws iam remove-role-from-instance-profile --instance-profile-name $PROFILE --role-name $ROLE_NAME
                                             } done
                                             aws iam delete-role --role-name  $ROLE_NAME
                                          } done
                                          
                                          aws iam delete-policy --policy-arn arn:aws:iam::123456789012:policy/EKS-Secret-Access-Policy
                                          \u001B[0m
                                          """,
                                  getVPCsCommand(),
                                  cleanEksCommand()
                                 ));
    }
}
