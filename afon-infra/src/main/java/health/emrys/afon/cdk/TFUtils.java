package health.emrys.afon.cdk;

import com.hashicorp.cdktf.TerraformResource;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.helm.release.ReleaseSet;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.*;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.infra.Defaults.POLARIS_SERVICE_NAME;
import static java.lang.String.format;

public class TFUtils {

    private final String    domainName;
    private final String    tlsSecretName;
    private final int       polarisPort;
    private final String    polarisInternal;
    private final Construct construct;

    public TFUtils(Construct construct, String domainName, String tlsSecretName, int polarisPort, String polarisInternal) {
        this.domainName      = domainName;
        this.tlsSecretName   = tlsSecretName;
        this.polarisPort     = polarisPort;
        this.polarisInternal = polarisInternal;
        this.construct       = construct;
    }

    public IngressV1 createIngress(List<TerraformResource> dependencies) {
        IngressV1SpecRuleHttpPathBackendServicePort port = IngressV1SpecRuleHttpPathBackendServicePort
            .builder()
            .number(polarisPort)
            .build();
        IngressV1SpecRuleHttpPathBackendService backend = IngressV1SpecRuleHttpPathBackendService
            .builder()
            .name(polarisInternal)
            .port(port)
            .build();
        IngressV1SpecRuleHttpPath httpPath = IngressV1SpecRuleHttpPath
            .builder()
            .path("/")
            .pathType("Prefix")
            .backend(IngressV1SpecRuleHttpPathBackend.builder()
                                                     .service(backend)
                                                     .build())
            .build();
        IngressV1SpecRule ingressV1SpecRule = IngressV1SpecRule
            .builder()
            .host(domainName)
            .http(IngressV1SpecRuleHttp.builder()
                                       .path(List.of(httpPath))
                                       .build())
            .build();
        IngressV1Spec nginxSpec = IngressV1Spec
            .builder()
            .ingressClassName("nginx")
            .tls(List.of(
                IngressV1SpecTls.builder()
                                .hosts(List.of(domainName))
                                .secretName(tlsSecretName)
                                .build()
                        ))
            .rule(List.of(ingressV1SpecRule))
            .build();
        return IngressV1.Builder
            .create(construct, POLARIS_SERVICE_NAME)
            .metadata(IngressV1Metadata.builder()
                                       .name(POLARIS_SERVICE_NAME)
                                       .annotations(Map.of(
                                           "cert-manager.io/cluster-issuer", "letsencrypt-prod"
                                                          ))
                                       .build())
            .dependsOn(dependencies)
            .provisioners(uponDestroyRun(PolarisConfig.NO_POLARIS_FINALIZERS))
            .spec(nginxSpec)
            .waitForLoadBalancer(true)
            .build();
    }

    public Release installNginxIngress(Construct construct,
                                       HelmProvider helmProvider,
                                       final java.util.List<? extends com.hashicorp.cdktf.ITerraformDependable> dependsOn) {
        return Release.Builder.create(construct, "nginx-ingress")
                              .provider(helmProvider)
                              .dependsOn(dependsOn)
                              .name("nginx-ingress")
                              .chart("ingress-nginx")
                              .repository("https://kubernetes.github.io/ingress-nginx")
                              .namespace("ingress-nginx")
                              .createNamespace(true)
                              .timeout(600)
                              .atomic(true)
                              .set(List.of(ReleaseSet.builder()
                                                     .name("controller.service.annotations.service\\.beta\\.kubernetes\\.io/azure-load-balancer-health-probe-request-path")
                                                     .value("/healthz")
                                                     .build()))
                              .build();
    }

    /**
     * Installs cert-manager and creates a Let's Encrypt ClusterIssuer.
     * The ClusterIssuer is applied via a local_exec provisioner rather than
     * kubernetes_manifest, which would contact the API server during plan
     * (before cert-manager's CRDs are registered) and fail with
     * "Failed to construct REST client".
     */
    public Release installCertManager(Construct construct,
                                      HelmProvider helmProvider,
                                      final java.util.List<? extends com.hashicorp.cdktf.ITerraformDependable> dependsOn) {
        String clusterIssuerYaml = format("""
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

        return Release.Builder.create(construct, "cert-manager")
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
