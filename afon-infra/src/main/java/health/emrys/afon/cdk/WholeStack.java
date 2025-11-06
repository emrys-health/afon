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
import com.hashicorp.cdktf.providers.kubernetes.manifest.Manifest;
import com.hashicorp.cdktf.providers.kubernetes.network_policy.*;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProvider;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccount;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccountMetadata;
import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.core.configuration.SparkContextConfigurationPrinter;
import org.jetbrains.annotations.NotNull;
import software.constructs.Construct;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.infra.Defaults.*;
import static health.emrys.afon.infra.Defaults.AWS_REGION;
import static java.lang.String.format;

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

        List<Resource> eksDependOn = List.of(newKubeConfig);

        // --- 4. EKS Node Group IAM Role & NEW Custom Policy ---

        final String workerAssumeRolePolicy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";
        final IamRole nodeGroupRole = IamRole.Builder.create(this, "eksNodeGroupRole")
                                                     .name("cdktf-eks-nodegroup-role")
                                                     .assumeRolePolicy(workerAssumeRolePolicy).build();

        // ** NEW STEP: Create Custom IAM Policy referencing the Secret ARN **
        // We use String.format to insert the secret's ARN dynamically into the policy JSON.
        // %s will be replaced by the Secret ARN
        final String customSecretPolicyDocument = format(
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
        String               secretName = "my-app-secret";
        AfonPropertiesLoader loader     = new AfonPropertiesLoader();
        Properties           properties = loader.getProperties();
        String               polarisEnv = "polaris_env.sh";
        Secret secret = Secret.Builder.create(this, "mySecret")
                                      .dependsOn(eksDependOn)
                                      .metadata(SecretMetadata.builder().name(secretName).build())
                                      .data(Map.of(
                                              polarisEnv, format("""
                                                                         export POLARIS_ID=root
                                                                         export POLARIS_SECRET=s3cr3t
                                                                         
                                                                         export %s=%s
                                                                         export %s=%s
                                                                         export %s=%s
                                                                         export %s=%s
                                                                         export %s=%s
                                                                         """,
                                                                 AWS_ACCESS_KEY_ID.getPropertyName(), AWS_ACCESS_KEY_ID.getValueFrom(properties),
                                                                 AWS_SECRET_ACCESS_KEY.getPropertyName(), AWS_SECRET_ACCESS_KEY.getValueFrom(properties),
                                                                 AZURE_CLIENT_ID.getPropertyName(), AZURE_CLIENT_ID.getValueFrom(properties),
                                                                 AZURE_TENANT_ID.getPropertyName(), AZURE_TENANT_ID.getValueFrom(properties),
                                                                 AZURE_CLIENT_SECRET.getPropertyName(), AZURE_CLIENT_SECRET.getValueFrom(properties)
                                                                ),
                                              "env.config", "ENVIRONMENT=production\nDEBUG=false"
                                                  )
                                           )
                                      .type("Opaque")
                                      .build();

        deployPolaris(secretName, polarisEnv, eksDependOn);
        String sparkConnectorName = "emrys2";

        deployJupyter(eksDependOn, sparkConnectorName);

        // 6. Configure the Helm Provider
        HelmProvider.Builder.create(this, "helm").build();

        calico(eksDependOn);

        csiSecretsDriver(eksDependOn);

        String serviceAccountName = "spark-application-sa";
        final ServiceAccount sparkApplicationSa = ServiceAccount.Builder.create(this, "sparkApplicationSa")
                                                                        .metadata(ServiceAccountMetadata.builder()
                                                                                                        .name(serviceAccountName)
                                                                                                        .namespace("default")
                                                                                                        .build())
                                                                        .build();

        // 8. INSTALL SPARK OPERATOR (CRD CONTROLLER)
        Release sparkOperatorRelease = Release.Builder.create(this, "sparkOperator")
                                                      .name("spark-kubernetes-operator")
                                                      .chart("spark-kubernetes-operator")
                                                      .repository("https://apache.github.io/spark-kubernetes-operator")
                                                      .namespace("default")
//                                                      .version("1.3.0")
                                                      .atomic(true)
                                                      .dependsOn(List.of(eksAuthData)) // Depend on EKS Auth
                                                      .build();


        // 9. DEPLOY SPARK CONNECT APPLICATION (Custom Resource Definition via Manifest)
        // This creates a dedicated Spark cluster whose driver acts as the Spark Connect server.

        deploySparkCluster(sparkConnectorName, serviceAccountName, sparkOperatorRelease, eksDependOn);
    }

    private void deployJupyter(List<Resource> eksDependOn, String sparkConnectorName) {
        final Map<String, String> jupyterLabels = Map.of("app", "jupyter");

        DeploymentSpecTemplateSpecContainer jupyterContainer = DeploymentSpecTemplateSpecContainer.builder()
                                                                                                  .name("jupyter")
                                                                                                  .image("ph1ll1phenry/pyspark-notebook:x86_64-spark-4.0.1")
                                                                                                  .build();
        var jupyterDeployment = deploy(eksDependOn, jupyterLabels, List.of(jupyterContainer), List.of(), "jupyter");

        Service.Builder.create(this, JUPYTER_SERVICE_NAME)
                       .metadata(ServiceMetadata.builder()
                                                .name(JUPYTER_SERVICE_NAME)
                                                .build())
                       .dependsOn(eksDependOn)
                       .spec(ServiceSpec.builder()
                                        .selector(jupyterLabels) // Selects the Pods created by secretAppDeployment
                                        .type("LoadBalancer")
                                        .port(List.of(
                                                ServiceSpecPort.builder()
                                                               .name("web")
                                                               .port(8888) // Port the load balancer listens on
                                                               .targetPort("8888") // Port the container is listening on
                                                               .protocol("TCP")
                                                               .build()
                                                     ))
                                        .build())
                       .build();

        Service.Builder.create(this, "jupyter-internal")
                       .metadata(ServiceMetadata.builder()
                                                .name("jupyter-internal")
                                                .namespace("default")
                                                .build())
                       .dependsOn(eksDependOn)
                       .spec(ServiceSpec.builder()
                                        .selector(jupyterLabels) // Selects the Pods created by secretAppDeployment
                                        .type("ClusterIP")
                                        .internalTrafficPolicy("Cluster")
                                        .clusterIp("None")
                                        .clusterIps(List.of())
                                        .port(List.of(
                                                ServiceSpecPort.builder()
                                                               .name("spark")
                                                               .port(7078) // Port the load balancer listens on
                                                               .targetPort("7078") // Port the container is listening on
                                                               .protocol("TCP")
                                                               .build()
                                                     ))
                                        .build())
                       .build();


        networkPolicies(jupyterLabels, sparkConnectorName);
    }

    private void deployPolaris(String secretName, String polarisEnv, List<Resource> eksDependOn) {
        String secretVolume = "secret-volume";
        List<DeploymentSpecTemplateSpecVolume> volumes = List.of(DeploymentSpecTemplateSpecVolume.builder()
                                                                                                 .name(secretVolume)
                                                                                                 .secret(DeploymentSpecTemplateSpecVolumeSecret.builder()
                                                                                                                                               .secretName(secretName)
                                                                                                                                               .build())
                                                                                                 .build());

        String secretsDir = "/etc/app-secrets";

        List<DeploymentSpecTemplateSpecContainerVolumeMount> mounts = List.of(DeploymentSpecTemplateSpecContainerVolumeMount.builder()
                                                                                                                            .name(secretVolume)
                                                                                                                            .mountPath(secretsDir)
                                                                                                                            .readOnly(true)
                                                                                                                            .build());

        List<String> commands = List.of("/bin/bash",
                                        "-c",
                                        format("source %s/%s && /opt/jboss/container/java/run/run-java.sh",
                                               secretsDir, polarisEnv));
        DeploymentSpecTemplateSpecContainer polarisContainer = DeploymentSpecTemplateSpecContainer.builder()
                                                                                                  .name("polaris")
                                                                                                  .image("apache/polaris:1.1.0-incubating")
//                                                                                                  .env(envVars)
                                                                                                  .command(commands)
                                                                                                  .volumeMount(mounts)
                                                                                                  .build();
        final Map<String, String> polarisLabels = Map.of("app", "polaris");
        var polarisDeployment = deploy(eksDependOn, polarisLabels, List.of(polarisContainer), volumes, "polaris");

        Service.Builder.create(this, POLARIS_SERVICE_NAME)
                       .metadata(ServiceMetadata.builder()
                                                .name(POLARIS_SERVICE_NAME)
                                                .build())
                       .dependsOn(List.of(polarisDeployment))
                       .spec(ServiceSpec.builder()
                                        .selector(polarisLabels)
                                        .type("LoadBalancer")
                                        .port(List.of(
                                                ServiceSpecPort.builder()
                                                               .name("web")
                                                               .port(8181)
                                                               .targetPort("8181")
                                                               .protocol("TCP")
                                                               .build()
                                                     ))
                                        .build())
                       .build();
    }

    private void deploySparkCluster(String sparkConnectorName,
                                    String serviceAccountName,
                                    Release sparkOperatorRelease,
                                    List<Resource> eksDependOn) {
        final Map<String, Object> sparkClusterManifest = Map.of(
                "apiVersion", "spark.apache.org/v1alpha1",
                "kind", "SparkCluster",
                "metadata", Map.of(
                        "name", "emrys",
                        "namespace", "default"
                                  ),
                "spec", Map.of(
                        "runtimeVersions", Map.of(
                                "sparkVersion", "4.0.1"
                                                 ),
                        "clusterTolerations", Map.of(
                                "instanceConfig", Map.of(
                                        "initWorkers", 2, // Integer type
                                        "minWorkers", 2, // Integer type
                                        "maxWorkers", 2  // Integer type
                                                        )
                                                    ),
                        "sparkConf", Map.of(
                                "spark.kubernetes.container.image", "apache/spark:4.0.1",
                                "spark.master.ui.title", "Prod Spark Cluster",
                                "spark.master.rest.enabled", "true",
                                "spark.master.rest.host", "0.0.0.0",
                                "spark.ui.reverseProxy", "true"
                                           )
                              )
                                                               );

        final Map<String, Object> sparkConnectManifest = Map.of(
                "apiVersion", "spark.apache.org/v1",
                "kind", "SparkApplication",
                "metadata", Map.of(
                        "name", sparkConnectorName,
                        "namespace", "default"
                                  ),
                "spec", Map.of(
                        "runtimeVersions", Map.of(
                                "sparkVersion", "4.0.1"
                                                 ),
                        "mainClass", "org.apache.spark.sql.connect.service.SparkConnectServer",
                        "sparkConf", sparkConfig(serviceAccountName),
                        "driverSpec", Map.of(
                                "podTemplateSpec", Map.of(
                                        "spec", Map.of()
                                                         )
                                            )
                              )
                                                               );

        Manifest.Builder.create(this, "sparkClusterManifest")
                        .manifest(sparkClusterManifest)
                        .dependsOn(List.of(sparkOperatorRelease))
                        .build();
        Manifest.Builder.create(this, "sparkConnectManifest")
                        .manifest(sparkConnectManifest)
                        .dependsOn(List.of(sparkOperatorRelease))
                        .build();

        Service.Builder.create(this, SPARK_SERVICE_NAME)
                       .metadata(ServiceMetadata.builder()
                                                .name(SPARK_SERVICE_NAME)
                                                .labels(Map.of("spark-role", "master",
                                                               "spark-version", "4.0.1",
                                                               "spark.operator/name", "spark-kubernetes-operator",
                                                               "spark.operator/spark-cluster-name", "prod")
                                                       )
                                                .build())
                       .dependsOn(eksDependOn)
                       .spec(ServiceSpec.builder()
                                        .selector(Map.of(
                                                "spark-role", "master"))
                                        .type("LoadBalancer")
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

    private Map<String, String> sparkConfig(String serviceAccountName) {
        var first = Map.of(
                "spark.kubernetes.container.image", "apache/spark:4.0.1",
                "spark.master.ui.title", "Prod Spark Cluster",
                "spark.master.rest.enabled", "true",
                "spark.master.rest.host", "0.0.0.0",
                "spark.ui.reverseProxy", "true",
                "spark.master", "spark://emrys-master-svc:7077",
                "spark.submit.deployMode", "cluster",
                "spark.executor.cores", "1",
                "spark.cores.max", "3",
                "spark.kubernetes.authenticate.driver.serviceAccountName", serviceAccountName
                          );
        var second = Map.of(
                "spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions",
                "spark.driver.allowMultipleContexts", "true",
                "spark.sql.iceberg.planning.preserve-data-grouping", "true",
                "spark.sql.sources.v2.bucketing.enabled", "true",
                "spark.sql.sources.bucketing.enabled", "true",
                "spark.jars.packages", "com.google.guava:guava:33.4.0-jre,org.apache.hadoop:hadoop-aws:3.4.2,com.google.cloud:google-cloud-storage:2.57.0,software.amazon.awssdk:bundle:2.34.6,org.apache.iceberg:iceberg-spark-runtime-4.0_2.13:1.10.0",
                "spark.driver.userClassPathFirst", "true",
                "spark.executor.userClassPathFirst", "true",
                "spark.driver.extraJavaOptions", "-Divy.home=/opt/spark/"
                           );
        var toReturn = new HashMap<String, String>();
        try {
            SparkContextConfigurationPrinter app           = SparkContextConfigurationPrinter.createSparkConf();
            var                              polarisConfig = app.getPolarisConfig();
            for (AfonProperties.SupportedCloudsEnum cloudProvider : AfonProperties.SupportedCloudsEnum.values()) {
                Map<String, String> configs = polarisConfig.sparkConfigs(cloudProvider);
                for (Map.Entry<String, String> entry : configs.entrySet()) {
                    toReturn.put(entry.getKey(), entry.getValue());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        toReturn.putAll(first);
        toReturn.putAll(second);
        return toReturn;
    }

    private void networkPolicies(Map<String, String> labels, String sparkConnectorName) {
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
                                                                                   .ports(webPorts)
                                                                                   .build(),
                                                            NetworkPolicySpecEgress.builder()
                                                                                   .to(List.of(dnsNetworkPolicy))
                                                                                   .ports(dnsPorts)
                                                                                   .build(),
                                                            NetworkPolicySpecEgress.builder()
                                                                                   .to(List.of(
                                                                                           NetworkPolicySpecEgressTo.builder()
                                                                                                                    .podSelector(NetworkPolicySpecEgressToPodSelector.builder().matchLabels(Map.of()).build())
                                                                                                                    .build()
                                                                                              ))
                                                                                   .build(),
                                                            NetworkPolicySpecEgress.builder()
                                                                                   .to(List.of(
                                                                                           NetworkPolicySpecEgressTo.builder()
                                                                                                                    // Placeholder CIDR for cluster service range (often 10.96.0.0/12 or 172.20.0.0/16, using a standard service CIDR block for safety)
                                                                                                                    .ipBlock(NetworkPolicySpecEgressToIpBlock.builder()
                                                                                                                                                             .cidr("10.96.0.0/12")
                                                                                                                                                             .build())
                                                                                                                    .build()
                                                                                              ))
                                                                                   .ports(List.of(
                                                                                           // API server usually runs on 443
                                                                                           NetworkPolicySpecEgressPorts.builder().protocol("TCP").port("443").build()
                                                                                                 ))
                                                                                   .build(),
                                                            NetworkPolicySpecEgress.builder()
                                                                                   .to(List.of(
                                                                                           NetworkPolicySpecEgressTo.builder()
                                                                                                                    .podSelector(NetworkPolicySpecEgressToPodSelector.builder()
                                                                                                                                                                     .matchLabels(Map.of("app.kubernetes.io/name", sparkConnectorName))
                                                                                                                                                                     .build())
                                                                                                                    .build()
                                                                                              ))
                                                                                   .ports(List.of(
                                                                                           NetworkPolicySpecEgressPorts.builder().protocol("TCP").port("15002").build()
                                                                                                 ))
                                                                                   .build(),
                                                            NetworkPolicySpecEgress.builder()
                                                                                   .to(List.of(
                                                                                           NetworkPolicySpecEgressTo.builder()
                                                                                                                    // Target the spark-operator namespace
                                                                                                                    .namespaceSelector(NetworkPolicySpecEgressToNamespaceSelector.builder()
                                                                                                                                                                                 .matchLabels(Map.of("kubernetes.io/metadata.name", "spark-operator"))
                                                                                                                                                                                 .build())
                                                                                                                    // Target the operator pod itself (optional, but good practice for specific control plane traffic)
                                                                                                                    .podSelector(NetworkPolicySpecEgressToPodSelector.builder()
                                                                                                                                                                     .matchLabels(Map.of("app.kubernetes.io/name", "spark-operator"))
                                                                                                                                                                     .build())
                                                                                                                    .build()
                                                                                              ))
                                                                                   .build()
                                                                   )
                                                           )
                                                    .build()
                                  ).build();
    }

    private void calico(List<@NotNull Resource> eksDependOn) {
        Release.Builder.create(this, "calicoOperator")
                       .name("tigera-operator")
                       .dependsOn(eksDependOn)
                       .chart("tigera-operator")
                       .cleanupOnFail(true)
                       .forceUpdate(true)
                       .repository("https://docs.tigera.io/calico/charts")
                       .namespace("tigera-operator") // Standard namespace for the operator
                       .createNamespace(true)
                       .version("3.31.0") // Using a recent stable version
                       .atomic(true)
                       .timeout(300)
                       .build();
    }

    private void csiSecretsDriver(List<@NotNull Resource> eksDependOn) {
        Release.Builder.create(this, "secrets-store-csi-driver")
                       .dependsOn(eksDependOn)
                       .name("csi-secrets-store") // The release name
                       .chart("secrets-store-csi-driver") // The chart name within the repository
                       .repository("https://kubernetes-sigs.github.io/secrets-store-csi-driver/charts")
                       .namespace("kube-system") // Target Kubernetes namespace
                       .timeout(300) // Wait up to 300 seconds for the deployment to stabilize
                       .atomic(true) // If installation fails, completely uninstall the release
                       .build();
    }

    private Deployment deploy(List<@NotNull Resource> eksDependOn,
                              Map<String, String> labels,
                              List<DeploymentSpecTemplateSpecContainer> containers,
                              List<DeploymentSpecTemplateSpecVolume> volumes,
                              String name) {
        DeploymentSpecTemplateMetadata metadata = DeploymentSpecTemplateMetadata.builder().labels(labels).build();
        DeploymentSpecTemplate specTemplate = DeploymentSpecTemplate.builder()
                                                                    .metadata(metadata)
                                                                    .spec(DeploymentSpecTemplateSpec.builder()
                                                                                                    .container(containers)
                                                                                                    .volume(volumes)
                                                                                                    .build())
                                                                    .build();
        return Deployment.Builder.create(this, name + "Deployment")
                          .dependsOn(eksDependOn)
                          .metadata(DeploymentMetadata.builder()
                                                      .name(name + "-deployment")
                                                      .labels(labels)
                                                      .build())
                          .spec(DeploymentSpec.builder()
                                              .selector(DeploymentSpecSelector.builder().matchLabels(labels).build())
                                              .replicas("1")
                                              .template(specTemplate)
                                              .build())
                          .build();
    }

    public static void main(String[] args) {
        System.out.println(new java.util.Date());
        final App app = new App();
        new WholeStack(app, "jupyter-deployment");
        app.synth();
    }
}