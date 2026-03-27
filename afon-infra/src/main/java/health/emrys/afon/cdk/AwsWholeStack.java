package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.TerraformResourceLifecycle;
import com.hashicorp.cdktf.providers.aws.iam_role.IamRole;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.provider.HelmProviderKubernetes;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.helm.release.ReleaseSet;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainer;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerEnv;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;
import com.hashicorp.cdktf.providers.kubernetes.network_policy.*;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccount;
import com.hashicorp.cdktf.providers.kubernetes.service_account.ServiceAccountMetadata;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static health.emrys.afon.cdk.CdkUtils.uponCreateRun;
import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.cdk.PolarisConfig.NO_JUPYTER_FINALIZERS;
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
public class AwsWholeStack extends AwsKubernetesPolaris {

    static final    Logger  logger                     = org.slf4j.LoggerFactory.getLogger(AwsWholeStack.class);
    static final    String  NO_INSTALLATION_FINALIZERS = "kubectl patch installation default -p '{\"metadata\":{\"finalizers\":[]}}' --type=merge";
    protected final Release calico;
    protected final List<Service> loadBalancers;
    private String DELETE_CRDS;

    public AwsWholeStack(final Construct scope, final SupportedCloudsEnum id) {
        super(scope, id);
        List<ITerraformDependable> dependables   = cloudProvider.getDependables();
        deployJupyter(dependables, SPARK_CONNECTOR_NAME, cloudProvider.loadBalancerRole);
        loadBalancers = List.of(); // Jupyter is now exposed via ALB ingress, not a raw LoadBalancer Service

        HelmProvider helm = HelmProvider.Builder.create(this, "helm")
                .kubernetes(HelmProviderKubernetes.builder()
                        .host(cloudProvider.getClusterEndpoint())
                        .clusterCaCertificate(com.hashicorp.cdktf.Fn.base64decode(cloudProvider.getBase64CertData()))
                        .token(cloudProvider.getToken())
                        .build())
                .build();
//        HelmProvider helm = HelmProvider.Builder.create(this, "helm").build();
        this.addOverride("terraform.required_providers.aws", Map.of(
            "source", "hashicorp/aws",
            "version", "6.27.0"   // 👈 Pin exact version
                                                                    ));
        this.addOverride("terraform.required_providers.helm", Map.of(
            "source", "hashicorp/helm",
            "version", "3.1.1"   // 👈 Pin exact version
                                                                    ));
        this.addOverride("terraform.required_providers.kubernetes", Map.of(
            "source", "hashicorp/kubernetes",
            "version", "3.0.1"   // 👈 Pin exact version
                                                                    ));

        calico = calico(dependables);

        csiSecretsDriver(dependables);

        ServiceAccount.Builder.create(this, "sparkApplicationSa")
                              .metadata(ServiceAccountMetadata.builder()
                                                              .name(SPARK_SERVICE_ACCOUNT_NAME)
                                                              .namespace("default")
                                                              .build())
                              .build();

        cloudProvider.configureLoadBalancerDestroy(loadBalancers);
        SparkConfig sparkConfig = new SparkConfig(this, "sparkop", dependables);

        unregisterCRDs();
    }

    protected void unregisterCRDs() {
        unregisterCRDs(List.of(calico));
    }

    protected void unregisterCRDs(List<? extends ITerraformDependable> dependsOn) {
        DELETE_CRDS = """
                for i in {1..5}; do {
                    echo iteration $i
                
                    for CRD in $(kubectl get crds | awk '{print $1}' | grep -v spark) ; do {
                     echo $CRD
                     timeout 30s kubectl patch crd $CRD --type=json -p='[{"op": "remove", "path": "/metadata/finalizers"}]' || echo could not patch $CRD 
                     timeout 30s kubectl delete crd $CRD  --force || echo could not delete $CRD
                    } done & 
                
                    sleep 60
                } done
                """;
        Resource.Builder.create(this, "cleanupCRDs")
                        .dependsOn(dependsOn)
                        .provisioners(uponDestroyRun(DELETE_CRDS))
                        .build();
        Resource.Builder.create(this, "cleanupCRDsNoDeps")
                        .provisioners(uponDestroyRun(DELETE_CRDS))
                        .build();
    }

    private void deployJupyter(List<ITerraformDependable> eksDependOn,
                               String sparkConnectorName,
                               IamRole loadBalancerRole) {
        final Map<String, String> jupyterLabels = Map.of("app", "jupyter");

        // Disable Jupyter's built-in token/password auth via env vars — oauth2-proxy handles authentication.
        // Using env vars avoids overriding the container CMD (which would break the tini entrypoint).
        DeploymentSpecTemplateSpecContainer jupyterContainer = DeploymentSpecTemplateSpecContainer
                .builder()
                .name("jupyter")
                .image("ph1ll1phenry/pyspark-notebook:x86_64-spark-4.0.1")
                .env(List.of(
                        // Disable Jupyter's built-in auth so oauth2-proxy is the sole gate.
                        // NOTEBOOK_ARGS is read by start-notebook.sh and forwarded to the server,
                        // avoiding the need to override the tini entrypoint / CMD.
                        // Both NotebookApp (classic) and ServerApp (Jupyter >= 7) flags are included
                        // so this works regardless of which server the image uses.
                        DeploymentSpecTemplateSpecContainerEnv.builder()
                                .name("NOTEBOOK_ARGS")
                                .value("--NotebookApp.token= --NotebookApp.password= --ServerApp.token= --ServerApp.password_required=False")
                                .build()
                    ))
                .build();
        DeploymentUtils.deploy(this,
                               eksDependOn,
                               jupyterLabels,
                               List.of(jupyterContainer),
                               List.of(),
                               "jupyter");

        // Internal ClusterIP so oauth2-proxy can reach the Jupyter web UI.
        Service jupyterWeb = Service.Builder.create(this, JUPYTER_SERVICE_NAME)
                                            .metadata(ServiceMetadata.builder()
                                                                      .name(JUPYTER_SERVICE_NAME)
                                                                      .namespace("default")
                                                                      .build())
                                            .dependsOn(eksDependOn)
                                            .provisioners(uponDestroyRun(NO_JUPYTER_FINALIZERS))
                                            .spec(ServiceSpec.builder()
                                                             .selector(jupyterLabels)
                                                             .type("ClusterIP")
                                                             .port(List.of(ServiceSpecPort.builder()
                                                                                          .name("web")
                                                                                          .port(8888)
                                                                                          .targetPort("8888")
                                                                                          .protocol("TCP")
                                                                                          .build()))
                                                             .build())
                                            .build();

        // Deploy oauth2-proxy pointing at the Jupyter web ClusterIP service.
        List<ITerraformDependable> proxyDeps = new ArrayList<>(eksDependOn);
        proxyDeps.add(jupyterWeb);
        new JupyterMfaConfig(this, proxyDeps, "http://jupyter-web:8888");

        // for emrysjupyter.click, this is Z03165251T5B540VMLNST
        // run aws route53 list-hosted-zones-by-name --dns-name emrysjupyter.click to see

        // ALB ingress: terminates HTTPS at the load balancer, routes to oauth2-proxy ClusterIP.
        // Traffic flow: internet → ALB (443) → oauth2-proxy:4180 → jupyter-web:8888 → Jupyter pod
        IngressV1 ingress = cloudProvider.createHttpsIngress(
                "jupyter-https-ingress",
                "jupyter-ingress",
                "arn:aws:acm:eu-west-2:123456789012:certificate/8f58f850-3756-40d4-b7b5-499124f150e3",
                JUPYTER_DOMAIN,
                JupyterMfaConfig.PROXY_SVC_NAME,
                JupyterMfaConfig.PROXY_PORT,
                cloudProvider.getSubnetIds());

        cloudProvider.configureDNS("JupyterAliasRecord",
                                   "Z03165251T5B540VMLNST", // aws route53 list-hosted-zones-by-name --dns-name emrysjupyter.click
                                   JUPYTER_DOMAIN, cloudProvider.getLbRelease(), ingress);

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

    private Release calico(List<@NotNull ITerraformDependable> eksDependOn) {
        /* This is still timing out after 10 minutes with:
│ Error: Error uninstalling release
│
│ Unable to uninstall Helm release tigera-operator: uninstallation completed with 1 error(s): context deadline exceeded
╵
        running tofu destroy a second time works.
         */
        return Release.Builder.create(this, "calicoOperator")
                              .name("tigera-operator")
                              .dependsOn(eksDependOn)
                              .chart("tigera-operator")
                              .version("v3.28.0")
                              .cleanupOnFail(true)
                              .disableCrdHooks(true)
                              .disableWebhooks(true)
                              .forceUpdate(true)
                              .recreatePods(false)
                              .set(List.of(
                                      ReleaseSet.builder().name("crds.install").value("true").build(),
                                      ReleaseSet.builder().name("crds.cleanup").value("false").build()
                                          ))
                              .provisioners(uponDestroyRun(NO_INSTALLATION_FINALIZERS,
                                                           "kubectl delete ns tigera-operator --force --grace-period=0"
                                                          ))
                              .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                              .repository("https://docs.tigera.io/calico/charts")
                              .namespace("tigera-operator") // Standard namespace for the operator
                              .createNamespace(true)
                              .version("3.31.0") // Using a recent stable version
                              .atomic(true)
                              .timeout(600)
                              .build();

        // kube-proxy must not run alongside Cilium's kube-proxy replacement — BPF and iptables
        // service routing conflict, breaking ClusterIP services (including CoreDNS).
        Resource.Builder.create(this, "disableKubeProxy")
                        .dependsOn(List.of(ciliumRelease))
                        .provisioners(Stream.concat(
                                uponCreateRun("kubectl -n kube-system patch daemonset kube-proxy -p '{\"spec\":{\"template\":{\"spec\":{\"nodeSelector\":{\"non-existing\":\"true\"}}}}}'").stream(),
                                uponDestroyRun("kubectl -n kube-system patch daemonset kube-proxy -p '{\"spec\":{\"template\":{\"spec\":{\"nodeSelector\":{\"kubernetes.io/os\":\"linux\"}}}}}'").stream()
                        ).toList())
                        .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                        .build();
        return ciliumRelease;
    }

    private void csiSecretsDriver(List<@NotNull ITerraformDependable> eksDependOn) {
        Release.Builder.create(this, "secrets-store-csi-driver")
                       .dependsOn(eksDependOn)
                       .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                       .name("csi-secrets-store") // The release name
                       .chart("secrets-store-csi-driver") // The chart name within the repository
                       .version("1.5.4")
                       .repository("https://kubernetes-sigs.github.io/secrets-store-csi-driver/charts")
                       .namespace("kube-system") // Target Kubernetes namespace
                       .timeout(300) // Wait up to 300 seconds for the deployment to stabilize
                       .atomic(false) // If installation fails, completely uninstall the release
                       .build();
    }

    public static void main(String[] args) {
        final App app = new App();
        new AwsWholeStack(app, ID);
        app.synth();
        logger.warn("Don't forget to export KUBE_CONFIG_PATH=$HOME/.kube/config and unalias aws before running tofu -apply");
    }
}