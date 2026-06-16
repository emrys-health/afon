package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.Fn;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.TerraformResourceLifecycle;
import com.hashicorp.cdktf.providers.aws.data_aws_eks_cluster.DataAwsEksCluster;
import com.hashicorp.cdktf.providers.aws.data_aws_eks_cluster.DataAwsEksClusterConfig;
import com.hashicorp.cdktf.providers.aws.data_aws_eks_cluster_auth.DataAwsEksClusterAuth;
import com.hashicorp.cdktf.providers.aws.data_aws_iam_policy.DataAwsIamPolicy;
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
import health.emrys.afon.cdk.AbstractConstruct;
import health.emrys.afon.config.AfonProperties;
import org.jetbrains.annotations.NotNull;
import software.constructs.Construct;

import java.util.*;
import java.util.stream.Collectors;

import static health.emrys.afon.cdk.aws.AwsKubernetesPolaris.POLARIS_INTERNAL;
import static health.emrys.afon.cdk.aws.AwsWholeStack.NO_INSTALLATION_FINALIZERS;
import static health.emrys.afon.cdk.CdkUtils.uponCreateRun;
import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.cdk.PolarisConfig.NO_JUPYTER_FINALIZERS;
import static health.emrys.afon.cdk.PolarisConfig.NO_POLARIS_FINALIZERS;
import static health.emrys.afon.config.AfonProperties.AWS_ACCOUNT_ID;
import static health.emrys.afon.config.AfonProperties.AWS_POLARIS_DNS_ZONE;
import static java.lang.String.format;

public class AwsConstruct extends AbstractConstruct {

    private final String                     clusterPolicyArn;
    final static  String                     NO_JUPYTER_LB = """
                                                             kubectl patch service jupyter-web -p \
                                                             '{"metadata":{"finalizers":null}}'  --type=merge""";
    final         DataAwsEksClusterAuth      eksAuthData;
    private final AwsComponentNames          componentNames;
    private final EksCluster                 cluster;
    private final List<ITerraformDependable> eksDependOn;
    private final List<String>               subnetIds;
    private final String                     polarisDomainName;
    private final String                     accountId;
    private final String                     region;
    private final String                     zone_id;
    private final String                     clusterName;
    private final AwsNuke                    awsNuke;
    private final String                     polarisCertArn;
    final         Vpc                        vpc;
    final         IamRole                    loadBalancerRole;
    final         Resource newKubeConfig;
    private       Release  lbRelease;

    AwsConstruct(final Construct scope, final String id, Properties properties) {
        super(scope, id);
        awsNuke        = new AwsNuke(properties);
        componentNames = new AwsComponentNames(properties);
        clusterName    = AfonProperties.CLUSTER_NAME.getValueFrom(properties);
        polarisCertArn = AfonProperties.AWS_POLARIS_CERT_ARN.getValueFrom(properties);

        this.polarisDomainName = AfonProperties.AWS_POLARIS_DOMAIN_NAME.getValueFrom(properties);
        this.accountId         = AWS_ACCOUNT_ID.getValueFrom(properties);
        this.region            = AfonProperties.AWS_REGION.getValueFrom(properties);
        this.zone_id           = AfonProperties.AWS_ZONE_ID.getValueFrom(properties);
        clusterPolicyArn       = format("arn:aws:iam::%s:policy/AWSLoadBalancerControllerIAMPolicyCurl", accountId);
        // --- AWS Provider Configuration (Required for EKS Addon) ---
        // 1. Configure the AWS Provider
        AwsProvider.Builder.create(this, "aws")
                           .region(region) // Set your desired AWS Region
                           .build();


        // --- 1. SECRETS MANAGER CREATION ---

        // Create the secret container
        final var applicationSecret = SecretsmanagerSecret.Builder
            .create(this, "ApplicationSecret")
            .name(componentNames.getSecret())
            .description("Secret containing application " +
                             "configuration or API keys for EKS.")
            .tags(Map.of("Name", "EKSAppConfig"))
            .recoveryWindowInDays(0)
            .build();

        vpc = Vpc.Builder
            .create(this, "eksVpc")
            .cidrBlock("10.0.0.0/16")
            .enableDnsSupport(true)
            .enableDnsHostnames(true)
            .provisioners(awsNuke.destroySGsAndVPC())
            .tags(Map.of("Name", componentNames.getVpcId())).build();

        final InternetGateway igw = InternetGateway.Builder
            .create(this, "vpcIgw")
            .vpcId(vpc.getId())
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .tags(Map.of("Name", "cdktf-eks-igw-" + clusterName)).build();

        final Subnet publicSubnet1 = Subnet.Builder
            .create(this, "publicSubnet1")
            .vpcId(vpc.getId())
            .cidrBlock("10.0.1.0/24")
            .availabilityZone(region + "a")
            .mapPublicIpOnLaunch(true)
            .dependsOn(List.of(igw))
            .provisioners(uponDestroyRun(awsNuke.cleanELBs()))
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .tags(Map.of("Name", "cdktf-eks-public-1-" + clusterName)).build();

        final Subnet publicSubnet2 = Subnet.Builder
            .create(this, "publicSubnet2")
            .vpcId(vpc.getId())
            .cidrBlock("10.0.2.0/24")
            .availabilityZone(region + "b")
            .mapPublicIpOnLaunch(true)
            .dependsOn(List.of(igw))
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .tags(Map.of("Name", "cdktf-eks-public-2-" + clusterName)).build();

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
                "cdktf-eks-public-rt-" + clusterName)).build();

        RouteTableAssociation subnet1Association = RouteTableAssociation.Builder
            .create(this, "subnet1Association")
            .subnetId(publicSubnet1.getId())
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .routeTableId(publicRouteTable.getId()).build();

        RouteTableAssociation subnet2Association = RouteTableAssociation.Builder
            .create(this, "subnet2Association")
            .subnetId(publicSubnet2.getId())
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .routeTableId(publicRouteTable.getId()).build();

        subnetIds = List.of(publicSubnet1.getId(), publicSubnet2.getId());

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

        final EksClusterVpcConfig vpcConfig = EksClusterVpcConfig.builder().subnetIds(subnetIds).build();

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

        final IamPolicy customSecretPolicy = IamPolicy.Builder
            .create(this, "SecretAccessPolicy")
            .name(componentNames.getAccessPolicyName())
            .description("Allows EKS Node Group to read the " +
                             "specific application config secret.")
            .policy(customSecretPolicyDocument)
            .build();

        // ** NEW ATTACHMENT: Attach the custom secret policy to the Node Group role **
        IamRolePolicyAttachment.Builder
            .create(this, "customSecretPolicyAttachment")
            .policyArn(customSecretPolicy.getArn())
            .role(nodeGroupRole.getName())
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .build();

        // Existing required policy attachments
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

        IamRolePolicyAttachment.Builder
            .create(this, "workerNodePolicyAttachment")
            .policyArn(workerNodePolicy.getArn())
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .role(nodeGroupRole.getName()).build();

        IamRolePolicyAttachment.Builder
            .create(this, "cniPolicyAttachment")
            .policyArn(cniPolicy.getArn())
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .role(nodeGroupRole.getName()).build();

        IamRolePolicyAttachment.Builder
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

        List<LocalExecProvisioner> updateKubeConfig = List.of(
            LocalExecProvisioner
                .builder()
                .when("create") // Run only when the resource is created
                .command(format(
                             "aws eks update-kubeconfig --name %s --region %s",
                             clusterName,
                             region)
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
            .subnetIds(subnetIds)
            .nodeRoleArn(nodeGroupRole.getArn())
            .instanceTypes(List.of("t3.medium"))
            .scalingConfig(scalingConfig)
            .dependsOn(List.of(
                newKubeConfig, cluster, eksAuthData,
                publicSubnet1, publicSubnet2))
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
        Release cilium = Release.Builder
            .create(this, "cilium")
            .name("cilium")
            .repository("https://helm.cilium.io/")
            .chart("cilium")
            .namespace("kube-system")
            .dependsOn(List.of(nodeGroup))
            .provisioners(onCiliumCreation)
            .version("1.15.0")
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
                // installs in a later stack (PrometheusClusterDeploy). The ServiceMonitor
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
            .repository("https://helm.cilium.io/")
            .chart("cilium")
            .version("1.15.0")
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
            .repository("https://kubernetes-sigs.github.io/metrics-server/")
            .chart("metrics-server")
            .version("3.12.2")
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

        String trustPolicy = format(
            """
            ${jsonencode({\"Version\": \"2012-10-17\", \"Statement\": [{\"Effect\": \"Allow\", \"Principal\": {\"Federated\": \"arn:aws:iam::%s:oidc-provider/${replace(data.aws_eks_cluster.EksClusterData.identity[0].oidc[0].issuer, \"https://\", \"\")}\"}, \"Action\": \"sts:AssumeRoleWithWebIdentity\", \"Condition\": {\"StringEquals\": {\"${replace(data.aws_eks_cluster.EksClusterData.identity[0].oidc[0].issuer, \"https://\", \"\")}:sub\": \"system:serviceaccount:kube-system:aws-load-balancer-controller\"}}}]})}
            """, accountId);
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

        IngressV1 ingress = createHttpsIngress(
            "polaris-https-ingress",
            "polaris-ingress",
            polarisCertArn,
            this.polarisDomainName,
            POLARIS_INTERNAL,
            8181,
            subnetIds
                                              );


        // Destroy-time cleanup: must run before lbRelease is uninstalled so the LBC
        // webhook doesn't block Helm and TargetGroupBinding finalizers can be removed.
        // The explicit helm uninstall --no-hooks pre-empts Terraform's own Helm destroy,
        // which fails when webhooks/finalizers interfere. Helm treats "release not found" as success.
        List<LocalExecProvisioner> onLbcDestroy = uponDestroyRun(
            "kubectl get validatingwebhookconfigurations -o name 2>/dev/null | grep aws-load-balancer" +
                " | xargs -r kubectl delete --ignore-not-found=true",
            "kubectl get mutatingwebhookconfigurations -o name 2>/dev/null | grep aws-load-balancer |" +
                " xargs -r kubectl delete --ignore-not-found=true",
            "kubectl get targetgroupbindings -A --no-headers 2>/dev/null | awk '{print $1, $2}' | " +
                "while read NS NAME; do kubectl patch targetgroupbinding \"$NAME\" -n \"$NS\" -p " +
                "'{\"metadata\":{\"finalizers\":[]}}' --type=merge 2>/dev/null || true; done",
            "kubectl patch ingress polaris-ingress -n default -p '{\"metadata\":{\"finalizers\":[]}}'" +
                " --type=merge 2>/dev/null || true",
            "helm uninstall aws-load-balancer-controller-chart -n kube-system --no-hooks 2>/dev/null " +
                "|| true",
            "kubectl delete secret sh.helm.release.v1.aws-load-balancer-controller-chart.v1 -n kube-system || true");
        Resource.Builder
            .create(this, "lbc-destroy-cleanup")
            .dependsOn(List.of(ingress, lbRelease))
            .provisioners(onLbcDestroy)
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .build();

        // 2. Create the Alias A Record
        // An Alias record is used to point a domain name at an AWS resource (like an ELB/ALB).
        // For the name, we use the specific subdomain (e.g., "app.mydomain.com").
        configureDNS("IngressAliasRecord",
                     AWS_POLARIS_DNS_ZONE.getValueFrom(properties),
                     this.polarisDomainName,
                     lbRelease,
                     ingress);
    }

    private static ReleaseSet createValue(String name, String value) {
        return ReleaseSet.builder()
                         .name(name)
                         .value(value)
                         .build();
    }

    /**
     * Creates a Route 53 Alias A record pointing {@code domainName} at the ALB provisioned by
     * the given Ingress. The ELB hosted zone ID {@code "ZHURV8PSTC4K8"} is fixed for eu-west-2.
     *
     * @param constructId unique CDKTF construct ID for this record
     * @param r53ZoneId   the Route 53 hosted zone ID that owns {@code domainName}
     * @param domainName  the domain to point at the ALB (e.g. {@code "emrysjupyter.click"})
     * @param release     Helm release the record should depend on
     * @param ingress     IngressV1 whose ALB hostname is used as the alias target
     */
    public void configureDNS(String constructId, String r53ZoneId, String domainName,
                             Release release, IngressV1 ingress) {
        String hostname = ingress.getStatus().get(0).getLoadBalancer().get(0).getIngress().get(0).getHostname();
        new Route53Record(
            this, constructId, Route53RecordConfig
            .builder()
            .zoneId(r53ZoneId)
            .name(domainName)
            .type("A")
            .allowOverwrite(true)
            .dependsOn(List.of(release))
            .alias(Route53RecordAlias
                       .builder()
                       .name(hostname)
                       .zoneId(zone_id)
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

        new IamRolePolicyAttachment(
            scope,
            "LBCPolicyAttachment",
            IamRolePolicyAttachmentConfig
                .builder()
                .dependsOn(List.of(loadBalancerRole))
                .role(loadBalancerRole.getName())
                .policyArn(clusterPolicyArn)
                .build());


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
        ServiceAccount.Builder
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
                NO_JUPYTER_LB,
                NO_POLARIS_FINALIZERS,
                NO_JUPYTER_FINALIZERS))
            .build();
        return serviceAccountName;
    }

    private @NotNull DataAwsEksCluster associateOIDCProvider(Construct scope) {
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
            .tags(Map.of("Name", clusterName + "-oidc-provider"))
            .build();
        new IamOpenidConnectProvider(scope, "EksIamOidcProvider", providerConfig);
        return clusterData;
    }

    private Release helmInstallLoadBalancer(String serviceAccountName, IamRole loadBalancerRole) {
        ArrayList<ITerraformDependable> dependencies = new ArrayList(eksDependOn);
        dependencies.add(loadBalancerRole);
        return Release.Builder
            .create(this, "aws-load-balancer")
            .dependsOn(dependencies)
            .name(serviceAccountName + "-chart")
            .chart("aws-load-balancer-controller")
            .version("1.17.0")
            .repository("https://aws.github.io/eks-charts")
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

    public IngressV1 createHttpsIngress(
        final String constructId,
        final String ingressName,
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
        return IngressV1.Builder
            .create(this, constructId)
            .provider(kubernetesProvider)
            .metadata(ingressMetadata)
            .spec(alb)
            .lifecycle(TerraformResourceLifecycle
                           .builder()
                           .preventDestroy(false)
                           .build())
            .waitForLoadBalancer(true)
            .build();
    }

    public Release getLbRelease() {
        return lbRelease;
    }

    public void configureLoadBalancerDestroy(List<Service> loadBalancers) {
        Resource.Builder
            .create(this, "cleanupELBs")
            // Ensure this runs only after the K8s Services are destroyed by Terraform
            .dependsOn(loadBalancers)
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .build();
        Resource.Builder
            .create(this, "cleanupSecurityGroups")
            .dependsOn(loadBalancers)
            .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
            .build();
    }


    public List<String> getSubnetIds() {
        return new ArrayList<>(subnetIds);
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

    public String getPolarisDomainName() {
        return polarisDomainName;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getRegion() {
        return region;
    }
}
