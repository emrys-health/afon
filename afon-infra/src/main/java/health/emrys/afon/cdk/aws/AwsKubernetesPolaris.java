package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.Fn;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.TerraformResourceLifecycle;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.helm.provider.HelmProviderKubernetes;
import com.hashicorp.cdktf.providers.kubernetes.deployment.Deployment;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProvider;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import health.emrys.afon.cdk.EmrysCloud;
import health.emrys.afon.cdk.JupyterDeployment;
import health.emrys.afon.cdk.JupyterHubConfig;
import health.emrys.afon.cdk.KeyCloakDeploymentConfig;
import health.emrys.afon.cdk.PolarisConfig;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.platform.ProvisionedCloud;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.cdk.PolarisConfig.POLARIS_INTERNAL;
import static health.emrys.afon.config.AfonProperties.AWS_JUPYTER_CERT_ARN;
import static health.emrys.afon.config.AfonProperties.AWS_JUPYTER_DNS_ZONE;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AWS;
import static health.emrys.afon.infra.Defaults.SPARK_SERVICE_NAME;

public class AwsKubernetesPolaris extends TerraformStack implements EmrysCloud, ProvisionedCloud {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(AwsKubernetesPolaris.class);

    public static final SupportedCloudsEnum ID               = AWS;

    protected final PolarisConfig polarisConfig;
    protected final AwsConstruct  cloudProvider;
    protected final Properties    properties;
    private         HelmProvider  lazyHelmProvider;

    private AwsKeyCloakDeploymentConfig keyCloakConfig;

    public AwsKubernetesPolaris(@NotNull Construct scope,
                                SupportedCloudsEnum id,
                                Properties properties) {
        super(scope, id.toString());
        this.properties = properties;
        NullProvider.Builder.create(this, "nullProvider")
                            .build();
        cloudProvider = new AwsConstruct(this, id.toString(), properties);
        polarisConfig = new PolarisConfig(this, "polaris", cloudProvider) {
            protected Service configurePolarisService(Deployment polarisDeployment, Map<String, String> polarisLabels) {
                return null; // no longer using an external service
            }
        };
        polarisConfig.deployPolaris(cloudProvider.getDependables());
        Service.Builder
            .create(this, POLARIS_INTERNAL)
            .metadata(ServiceMetadata
                          .builder()
                          .name(POLARIS_INTERNAL)
                          .namespace("default")
                          .build())
            .dependsOn(cloudProvider.getDependables())
            .spec(ServiceSpec
                      .builder()
                      .selector(polarisConfig.polarisLabels) // Selects the Pods created by secretAppDeployment
                      .type("NodePort")
                      .internalTrafficPolicy("Cluster")
                      .clusterIps(List.of())
                      .port(List.of(polarisConfig.POLARIS_PORTS))
                      .build())
            .build();
        keyCloakConfig = new AwsKeyCloakDeploymentConfig(this, properties, cloudProvider);
        keyCloakConfig.configureHttpsAndDNS();
    }

    /**
     * Keycloak's AWS wiring, exposed so subclasses can depend on its DNS record (see
     * {@link AwsKeyCloakDeploymentConfig#getAliasRecord()}).
     */
    protected AwsKeyCloakDeploymentConfig getKeyCloakConfig() {
        return keyCloakConfig;
    }

    public static void main(String[] args) {
        final App app = new App();
        new AwsKubernetesPolaris(app, ID, new AfonPropertiesLoader().getProperties());
        app.synth();
        logger.warn("Don't forget to export KUBE_CONFIG_PATH=$HOME/.kube/config and unalias aws before running tofu " +
                        "-apply");
    }

    @Override
    public SupportedCloudsEnum getId() {
        return ID;
    }

    @Override
    public String getPolarisDomainName() {
        return cloudProvider.getPolarisDomainName();
    }

    // -------------------------------------------------------------------------
    // ProvisionedCloud implementation
    // -------------------------------------------------------------------------

    @Override
    public String cloudId() {
        return ID.toString();
    }

    @Override
    public TerraformStack stack() {
        return this;
    }

    @Override
    public List<ITerraformDependable> dependables() {
        return cloudProvider.getDependables();
    }

    @Override
    public List<ITerraformDependable> workloadReadiness() {
        return cloudProvider.getDependables();
    }

    @Override
    public KubernetesProvider k8sProvider() {
        return cloudProvider.configureK8s();
    }

    @Override
    public synchronized HelmProvider helmProvider() {
        if (lazyHelmProvider == null) {
            HelmProviderKubernetes kubernetes = HelmProviderKubernetes
                .builder()
                .host(cloudProvider.getClusterEndpoint())
                .clusterCaCertificate(Fn.base64decode(cloudProvider.getBase64CertData()))
                .token(cloudProvider.getToken())
                .build();
            lazyHelmProvider = HelmProvider.Builder
                .create(this, "helm")
                .kubernetes(kubernetes)
                .build();
        }
        return lazyHelmProvider;
    }

    @Override
    public String polarisDomainName() {
        return getPolarisDomainName();
    }

    @Override
    public JupyterDeployment newJupyterConfig() {
        JupyterHubConfig jupyterHub = new JupyterHubAwsConfig(
            this,
            cloudProvider,
            properties,
            getKeyCloakConfig().getAliasRecord());
        return dependsOn -> {
            // Every Service the hub deploy creates (guacamole-web, the per-user VNC services,
            // jupyterhub-proxy-public) fires the AWS LB Controller's mutating webhook
            // (mservice.elbv2.k8s.aws), which intercepts all Service creations — not just
            // type: LoadBalancer. Depend on the controller's Helm release — installed atomically,
            // so Terraform waits for its pods to be Ready — otherwise the webhook Service can still
            // have zero endpoints and the apply fails with "no endpoints available for service
            // aws-load-balancer-webhook-service". Same reasoning as sparkExternalService's LB Service.
            List<ITerraformDependable> jupyterDependables = new ArrayList<>(dependsOn);
            jupyterDependables.add(cloudProvider.getLbRelease());
            return jupyterHub.deployJupyterHub(jupyterDependables);
        };
    }

    /**
     * ALB ingress terminating HTTPS at the load balancer plus a Route53 alias
     * record — the same wiring {@code JupyterAwsConfig#configureHttpsAndDNS}
     * uses. The certificate and hosted zone still come from the workbench
     * properties ({@code AWS_JUPYTER_CERT_ARN} / {@code AWS_JUPYTER_DNS_ZONE});
     * a deployment has at most one workbench, so they are shared.
     */
    @Override
    public void exposeWorkbench(String ingressName, String domain, String serviceName, int servicePort) {
        IngressV1 ingress = cloudProvider.createHttpsIngress(
            ingressName + "-https-ingress",
            ingressName,
            AWS_JUPYTER_CERT_ARN.getValueFrom(properties),
            domain,
            serviceName,
            servicePort,
            cloudProvider.getSubnetIds());

        cloudProvider.configureDNS(
            ingressName + "AliasRecord",
            AWS_JUPYTER_DNS_ZONE.getValueFrom(properties),
            domain, cloudProvider.getLbRelease(), ingress, ingressName);
    }

    @Override
    public boolean lockDownJupyterEgress() {
        return true;
    }

    /**
     * Moved verbatim from {@code AwsWholeStack}'s constructor (which now calls
     * this) so the config-driven planner can apply the same hardening: pinned
     * provider versions, the secrets-store CSI driver, and load-balancer
     * destroy handling.
     */
    @Override
    public void hardenStack() {
        helmProvider();
        this.addOverride(
            "terraform.required_providers.aws", Map.of(
                "source", "hashicorp/aws",
                "version", "6.27.0"   // 👈 Pin exact version
                                                      ));
        this.addOverride(
            "terraform.required_providers.helm", Map.of(
                "source", "hashicorp/helm",
                "version", "3.1.1"   // 👈 Pin exact version
                                                       ));
        this.addOverride(
            "terraform.required_providers.kubernetes", Map.of(
                "source", "hashicorp/kubernetes",
                "version", "3.0.1"   // 👈 Pin exact version
                                                             ));
        csiSecretsDriver(cloudProvider.getDependables());
        cloudProvider.configureLoadBalancerDestroy(List.of());
    }

    /**
     * Moved verbatim from {@code SparkClusterDeployment} (which now calls
     * this): the spark-master LoadBalancer Service plus the CRD-cleanup
     * destroy provisioners, so the config-driven planner reproduces the
     * legacy AWS Spark deployment.
     */
    @Override
    public void exposeSparkMaster(List<ITerraformDependable> sparkReady) {
        Service sparkLB = sparkExternalService(sparkReady);
        unregisterCRDs(List.of(sparkLB));
    }

    static final String NO_SPARK_LB_FINALIZERS = "kubectl patch service spark-master-lb -p '{\"metadata\":{\"finalizers\":null}}'  --type=merge";

    private Service sparkExternalService(List<ITerraformDependable> eksDependOn) {
        String                          subnetIds      = cloudProvider.getSubnetIds().stream().collect(Collectors.joining(","));
        ArrayList<ITerraformDependable> lbDependencies = new ArrayList<>(eksDependOn);
        lbDependencies.add(cloudProvider.loadBalancerRole);
        // Creating this LoadBalancer Service fires the AWS LB Controller's
        // mutating webhook (mservice.elbv2.k8s.aws). Depend on the controller's
        // Helm release — which is installed atomically, so Terraform waits for
        // its pods to be Ready — otherwise the webhook Service can still have
        // zero endpoints and the apply fails with "no endpoints available for
        // service aws-load-balancer-webhook-service".
        lbDependencies.add(cloudProvider.getLbRelease());
        return Service.Builder.create(this, SPARK_SERVICE_NAME)
                              .metadata(ServiceMetadata.builder()
                                                       .name(SPARK_SERVICE_NAME)
                                                       .labels(Map.of("spark-role", "master",
                                                                      "spark-version", "4.0.1",
                                                                      "spark.operator/name", "spark-kubernetes-operator",
                                                                      "spark.operator/spark-cluster-name", "prod"
                                                                     ))
                                                       .annotations(Map.of(
                                                               "service.beta.kubernetes.io/aws-load-balancer-subnets", subnetIds,
                                                               "service.beta.kubernetes.io/aws-load-balancer-internal", "false"))
                                                       .build())
                              .dependsOn(lbDependencies)
                              .provisioners(uponDestroyRun(NO_SPARK_LB_FINALIZERS))
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

    protected void unregisterCRDs(List<? extends ITerraformDependable> dependsOn) {
        String deleteCrds = """
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
        Resource.Builder
            .create(this, "cleanupCRDs")
            .dependsOn(dependsOn)
            .provisioners(uponDestroyRun(deleteCrds))
            .build();
        Resource.Builder
            .create(this, "cleanupCRDsNoDeps")
            .provisioners(uponDestroyRun(deleteCrds))
            .build();
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
                       .atomic(true) // If installation fails, completely uninstall the release
                       .build();
    }
}
