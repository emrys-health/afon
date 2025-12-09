package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.TerraformResourceLifecycle;
import com.hashicorp.cdktf.providers.aws.iam_role.IamRole;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainer;
import com.hashicorp.cdktf.providers.kubernetes.network_policy.*;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccount;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccountMetadata;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.infra.Defaults.*;

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
class AwsWholeStack extends AwsKubernetesPolaris {

    static final Logger logger                     = org.slf4j.LoggerFactory.getLogger(AwsWholeStack.class);
    static final String NO_INSTALLATION_FINALIZERS = "kubectl patch installation default -p '{\"metadata\":{\"finalizers\":[]}}' --type=merge";

    public AwsWholeStack(final Construct scope, final String id) {
        super(scope, id);
        Service       jupyterLB     = deployJupyter(cloudProvider.getDependables(), SPARK_CONNECTOR_NAME, cloudProvider.loadBalancerRole);
        List<Service> loadBalancers = List.of(jupyterLB); // no long exposing HTTP Polaris

        // 6. Configure the Helm Provider
        HelmProvider.Builder.create(this, "helm").build();

        calico(cloudProvider.getDependables());

        csiSecretsDriver(cloudProvider.getDependables());

        ServiceAccount.Builder.create(this, "sparkApplicationSa")
                              .metadata(ServiceAccountMetadata.builder()
                                                              .name(SPARK_SERVICE_ACCOUNT_NAME)
                                                              .namespace("default")
                                                              .build())
                              .build();

        cloudProvider.configureLoadBalancerDestroy(loadBalancers);
        SparkConfig sparkConfig = new SparkConfig(this, "sparkop", cloudProvider.getDependables());
    }

    private Service deployJupyter(List<ITerraformDependable> eksDependOn, String sparkConnectorName, IamRole loadBalancerRole) {
        final Map<String, String> jupyterLabels = Map.of("app", "jupyter");

        DeploymentSpecTemplateSpecContainer jupyterContainer = DeploymentSpecTemplateSpecContainer
                .builder()
                .name("jupyter")
                .image("ph1ll1phenry/pyspark-notebook:x86_64-spark-4.0.1")
                .build();
        DeploymentUtils.deploy(this,
                               eksDependOn,
                               jupyterLabels,
                               List.of(jupyterContainer),
                               List.of(),
                               "jupyter");

        ArrayList<ITerraformDependable> lbDependencies = new ArrayList<>(eksDependOn);
        lbDependencies.add(loadBalancerRole);
        Service loadbalancer = Service.Builder.create(this, JUPYTER_SERVICE_NAME)
                                              .metadata(ServiceMetadata.builder()
                                                                       .name(JUPYTER_SERVICE_NAME)
                                                                       .build())
                                              .dependsOn(lbDependencies)
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
                       .dependsOn(eksDependOn).lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
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

        return loadbalancer;
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

    private void calico(List<@NotNull ITerraformDependable> eksDependOn) {
        /* This is still timing out after 10 minutes with:
│ Error: Error uninstalling release
│
│ Unable to uninstall Helm release tigera-operator: uninstallation completed with 1 error(s): context deadline exceeded
╵
        running tofu destroy a second time works.
         */
        Release.Builder.create(this, "calicoOperator")
                       .name("tigera-operator")
                       .dependsOn(eksDependOn)
                       .chart("tigera-operator")
                       .cleanupOnFail(true)
                       .disableCrdHooks(true)
                       .disableWebhooks(true)
                       .forceUpdate(true)
                       .provisioners(uponDestroyRun(NO_INSTALLATION_FINALIZERS))
                       .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                       .repository("https://docs.tigera.io/calico/charts")
                       .namespace("tigera-operator") // Standard namespace for the operator
                       .createNamespace(true)
                       .version("3.31.0") // Using a recent stable version
                       .atomic(true)
                       .timeout(900)
                       .build();
    }

    private void csiSecretsDriver(List<@NotNull ITerraformDependable> eksDependOn) {
        Release.Builder.create(this, "secrets-store-csi-driver")
                       .dependsOn(eksDependOn)
                       .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                       .name("csi-secrets-store") // The release name
                       .chart("secrets-store-csi-driver") // The chart name within the repository
                       .repository("https://kubernetes-sigs.github.io/secrets-store-csi-driver/charts")
                       .namespace("kube-system") // Target Kubernetes namespace
                       .timeout(300) // Wait up to 300 seconds for the deployment to stabilize
                       .atomic(true) // If installation fails, completely uninstall the release
                       .build();
    }

    public static void main(String[] args) {
        final App app = new App();
        new AwsWholeStack(app, ID);
        app.synth();
        logger.warn("Don't forget to export KUBE_CONFIG_PATH=$HOME/.kube/config and unalias aws before running tofu -apply");
    }
}