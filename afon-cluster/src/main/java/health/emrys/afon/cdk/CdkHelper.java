package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.TerraformResource;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.helm.release.ReleaseSet;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerEnv;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerEnvValueFrom;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerEnvValueFromSecretKeyRef;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.*;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.cluster.Defaults.POLARIS_INGRESS_NAME;
import static java.lang.String.format;

public class CdkHelper {

    public static final String                     NO_POLARIS_FINALIZERS
        = finalizeIngress(POLARIS_INGRESS_NAME);
    public static final List<LocalExecProvisioner> POLARIS_PROVISIONERS
        = uponDestroyRun(NO_POLARIS_FINALIZERS);

    public static String finalizeIngress(String service) {
        return format("kubectl patch ingress %s -p '{\"metadata\":{\"finalizers\":[]}}' --type=merge", service);
    }

    public static DeploymentSpecTemplateSpecContainerEnv envVar(String name, String value) {
        return DeploymentSpecTemplateSpecContainerEnv.builder()
                                                     .name(name)
                                                     .value(value)
                                                     .build();
    }

    public static @NonNull Service clusterIpService(java.util.List<? extends ITerraformDependable> dependsOn,
                                                    Map<String, String> labels,
                                                    String serviceName,
                                                    List<ServiceSpecPort> ports,
                                                    Construct scope) {
        return typedService("ClusterIP", dependsOn, labels, serviceName, ports, scope);
    }

    /**
     * A NodePort service is required when the backing workload is exposed via the AWS Load
     * Balancer Controller with the default {@code target-type: instance}: the ALB forwards to a
     * node port on each EC2 node, so a ClusterIP service (which has no node port) makes the LBC
     * fail with "TargetGroup port is empty" and the ALB then serves "Backend service does not
     * exist".
     */
    public static @NonNull Service nodePortService(java.util.List<? extends ITerraformDependable> dependsOn,
                                                   Map<String, String> labels,
                                                   String serviceName,
                                                   List<ServiceSpecPort> ports,
                                                   Construct scope) {
        return typedService("NodePort", dependsOn, labels, serviceName, ports, scope);
    }

    private static @NonNull Service typedService(String type,
                                                 java.util.List<? extends ITerraformDependable> dependsOn,
                                                 Map<String, String> labels,
                                                 String serviceName,
                                                 List<ServiceSpecPort> ports,
                                                 Construct scope) {
        return Service.Builder
            .create(scope, serviceName)
            .metadata(ServiceMetadata
                          .builder()
                          .name(serviceName)
                          .build())
            .dependsOn(dependsOn)
            .spec(ServiceSpec
                      .builder()
                      .selector(labels)
                      .type(type)
                      .port(ports)
                      .build())
            .build();
    }

    public static Release installNginxIngress(
        Construct construct,
        HelmProvider helmProvider,
        final java.util.List<? extends com.hashicorp.cdktf.ITerraformDependable> dependsOn,
        Map<String, String> annotations) {
        var builder = Release.Builder
            .create(construct, "nginx-ingress")
            .provider(helmProvider)
            .dependsOn(dependsOn)
            .name("nginx-ingress")
            .chart("ingress-nginx")
            .repository("https://kubernetes.github.io/ingress-nginx")
            .namespace("ingress-nginx")
            .createNamespace(true)
            .timeout(600)
            .atomic(true);
        if (annotations != null && !annotations.isEmpty()) {
            var releaseSetBuilder = ReleaseSet.builder();
            annotations.forEach((name, value) -> {
                releaseSetBuilder.name(name);
                releaseSetBuilder.value(value);
            });
            builder.set(List.of(releaseSetBuilder.build()));
        }
        return builder.build();
    }

    static DeploymentSpecTemplateSpecContainerEnv envFromSecret(
        String envName, String secretName, String secretKey) {
        return DeploymentSpecTemplateSpecContainerEnv
            .builder()
            .name(envName)
            .valueFrom(DeploymentSpecTemplateSpecContainerEnvValueFrom
                           .builder()
                           .secretKeyRef(DeploymentSpecTemplateSpecContainerEnvValueFromSecretKeyRef
                                             .builder()
                                             .name(secretName)
                                             .key(secretKey)
                                             .build())
                           .build())
            .build();
    }

    private final String    domainName;
    private final String    tlsSecretName;
    private final int       port;
    private final String    internalService;
    private final Construct construct;
    private final String    ingressName;

    public CdkHelper(
        Construct construct,
        String domainName,
        String tlsSecretName,
        int port,
        String internalService,
        String ingressName) {
        this.domainName      = domainName;
        this.tlsSecretName   = tlsSecretName;
        this.port            = port;
        this.internalService = internalService;
        this.construct       = construct;
        this.ingressName     = ingressName;
    }

    public IngressV1 createIngress(
        List<TerraformResource> dependencies,
        Optional<List<LocalExecProvisioner>> ingressProvisioners) {
        IngressV1SpecRuleHttpPathBackendServicePort port = IngressV1SpecRuleHttpPathBackendServicePort
            .builder()
            .number(this.port)
            .build();
        IngressV1SpecRuleHttpPathBackendService backend = IngressV1SpecRuleHttpPathBackendService
            .builder()
            .name(internalService)
            .port(port)
            .build();
        IngressV1SpecRuleHttpPath httpPath = IngressV1SpecRuleHttpPath
            .builder()
            .path("/")
            .pathType("Prefix")
            .backend(IngressV1SpecRuleHttpPathBackend
                         .builder()
                         .service(backend)
                         .build())
            .build();
        IngressV1SpecRule ingressV1SpecRule = IngressV1SpecRule
            .builder()
            .host(domainName)
            .http(IngressV1SpecRuleHttp
                      .builder()
                      .path(List.of(httpPath))
                      .build())
            .build();
        IngressV1Spec nginxSpec = IngressV1Spec
            .builder()
            .ingressClassName("nginx")
            .tls(List.of(
                IngressV1SpecTls
                    .builder()
                    .hosts(List.of(domainName))
                    .secretName(tlsSecretName)
                    .build()
            ))
            .rule(List.of(ingressV1SpecRule))
            .build();
        IngressV1.Builder ingressBuilder = IngressV1.Builder
            .create(construct, ingressName)
            .metadata(IngressV1Metadata
                          .builder()
                          .name(ingressName)
                          .annotations(Map.of(
                              "cert-manager.io/cluster-issuer", "letsencrypt-prod"
                          ))
                          .build())
            .dependsOn(dependencies)
            .spec(nginxSpec)
            .waitForLoadBalancer(true);
        ingressProvisioners.stream().forEach(x -> ingressBuilder.provisioners(x));
        return ingressBuilder
            .build();
    }

    /**
     * Installs cert-manager and creates a Let's Encrypt ClusterIssuer.
     * The ClusterIssuer is applied via a local_exec provisioner rather than
     * kubernetes_manifest, which would contact the API server during plan
     * (before cert-manager's CRDs are registered) and fail with
     * "Failed to construct REST client".
     */
    public Release installCertManager(
        Construct construct,
        HelmProvider helmProvider,
        final java.util.List<? extends com.hashicorp.cdktf.ITerraformDependable> dependsOn) {
        String clusterIssuerYaml = format(
            """
            kubectl apply -f - <<'YAML'
            apiVersion: cert-manager.io/v1
            kind: ClusterIssuer
            metadata:
              name: letsencrypt-prod
            spec:
              acme:
                server: https://acme-v02.api.letsencrypt.org/directory
                email: admin@%s
                privateKeySecretRef:
                  name: letsencrypt-prod
                solvers:
                - http01:
                    ingress:
                      class: nginx
            YAML""", domainName);

        return Release.Builder
            .create(construct, "cert-manager")
            .provider(helmProvider)
            .dependsOn(dependsOn)
            .name("cert-manager")
            .chart("cert-manager")
            .repository("https://charts.jetstack.io")
            .namespace("cert-manager")
            .createNamespace(true)
            .timeout(300)
            .atomic(true)
            .set(List.of(ReleaseSet.builder().name("crds.enabled").value("true").build()))
            .provisioners(CdkUtils.uponCreateRun(clusterIssuerYaml))
            .build();
    }
}
