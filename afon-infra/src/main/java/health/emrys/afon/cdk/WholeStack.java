package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.kubernetes.deployment.*;
import com.hashicorp.cdktf.providers.kubernetes.deployment.Deployment;
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
import health.emrys.afon.config.AfonPropertiesLoader;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.infra.Defaults.*;
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

    static final        Logger logger = org.slf4j.LoggerFactory.getLogger(WholeStack.class);

    public static final String ID     = "jupyter-deployment";

    public WholeStack(final Construct scope, final String id) {
        super(scope, id);

        NullProvider.Builder.create(this, "nullProvider")
                            .build();

        CloudProvider cloudProvider = new AwsConstruct(this, id);

        // --- 4. EKS Node Group IAM Role & NEW Custom Policy ---

        // --- Kubernetes and Helm Provider Configuration (Original Code) ---

        // 3. Configure the Kubernetes Provider
        String base64CertData = cloudProvider.getBase64CertData();
        String encodedCert    = com.hashicorp.cdktf.Fn.base64decode(base64CertData);
        KubernetesProvider kubernetesProvider = KubernetesProvider.Builder.create(this, "kubernetes")
                                                                          // Direct EKS Connection Configuration:
                                                                          .host(cloudProvider.getEndpoint())
                                                                          .clusterCaCertificate(encodedCert)
                                                                          .token(cloudProvider.getToken()) // Dynamically generated token
                                                                          .build();
//        kubernetesProvider.addOverride("depends_on", List.of(eksDependOn));

        // 4. Define a Kubernetes Secret
        // The data must be Base64 encoded. Each key in the secret will become a file in the volume mount.
        String               secretName = "my-app-secret";
        AfonPropertiesLoader loader     = new AfonPropertiesLoader();
        Properties           properties = loader.getProperties();
        String               polarisEnv = "polaris_env.sh";
        Secret secret = Secret.Builder.create(this, "mySecret")
                                      .dependsOn(cloudProvider.getDependables())
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

        deployPolaris(secretName, polarisEnv, cloudProvider.getDependables());
        deployJupyter(cloudProvider.getDependables(), SPARK_CONNECTOR_NAME);

        // 6. Configure the Helm Provider
        HelmProvider.Builder.create(this, "helm").build();

        calico(cloudProvider.getDependables());

        csiSecretsDriver(cloudProvider.getDependables());

        final ServiceAccount sparkApplicationSa = ServiceAccount.Builder.create(this, "sparkApplicationSa")
                                                                        .metadata(ServiceAccountMetadata.builder()
                                                                                                        .name(SPARK_SERVICE_ACCOUNT_NAME)
                                                                                                        .namespace("default")
                                                                                                        .build())
                                                                        .build();


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
        new WholeStack(app, ID);
        app.synth();
        logger.warn("Don't forget to export KUBE_CONFIG_PATH=$HOME/.kube/config and unalias aws before running tofu -apply");
    }
}