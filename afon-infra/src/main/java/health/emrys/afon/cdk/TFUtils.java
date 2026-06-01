package health.emrys.afon.cdk;

import com.hashicorp.cdktf.TerraformResource;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.helm.release.ReleaseSet;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerEnv;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.*;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.infra.Defaults.POLARIS_SERVICE_NAME;
import static java.lang.String.format;

public class TFUtils {

    public static DeploymentSpecTemplateSpecContainerEnv envVar(String name, String value) {
        return DeploymentSpecTemplateSpecContainerEnv.builder()
                                                     .name(name)
                                                     .value(value)
                                                     .build();
    }

    private final String       domainName;
    private final String       tlsSecretName;
    private final int          polarisPort;
    private final String       polarisInternal;
    private final Construct    construct;
    private final TlsCertStore certStore;

    /**
     * The TLS secret cert-manager populates lives in the same namespace as the
     * Ingress created by {@link #createIngress(List)}, which sets no namespace
     * and therefore lands in {@code default}.
     */
    private static final String TLS_SECRET_NAMESPACE = "default";

    public TFUtils(Construct construct,
                   String domainName,
                   String tlsSecretName,
                   int polarisPort,
                   String polarisInternal,
                   TlsCertStore certStore) {
        this.domainName      = domainName;
        this.tlsSecretName   = tlsSecretName;
        this.polarisPort     = polarisPort;
        this.polarisInternal = polarisInternal;
        this.construct       = construct;
        this.certStore       = certStore;
    }

    /**
     * Local staging path for the cert blob in transit between Kubernetes and
     * the cloud secret store. Ephemeral — written, pushed/pulled, then removed
     * within a single provisioner run, so {@code /tmp} is fine.
     */
    private String certStagingFile() {
        return "/tmp/afon-" + tlsSecretName + ".tls";
    }

    /**
     * Destroy-time command: while the cluster is still up, dump the issued cert
     * and key as a single {@code "<crt-b64> <key-b64>"} line and hand it to the
     * cloud secret store ({@link TlsCertStore#uploadCommand}). Single-line so
     * the value survives Key Vault / Secret Manager round-trips without newline
     * mangling. Guarded so a missing secret never overwrites a stored copy.
     */
    private String backupTlsSecretCommand() {
        String staging = certStagingFile();
        return format("""
            if kubectl get secret %1$s -n %2$s >/dev/null 2>&1; then
              kubectl get secret %1$s -n %2$s -o jsonpath='{.data.tls\\.crt} {.data.tls\\.key}' > %3$s
              %4$s
              rm -f %3$s
              echo "Backed up TLS secret %1$s to the cloud secret store"
            else
              echo "No TLS secret %1$s to back up; keeping any stored copy"
            fi""", tlsSecretName, TLS_SECRET_NAMESPACE, staging,
            certStore.uploadCommand(tlsSecretName, staging));
    }

    /**
     * Create-time command: pull the stored blob from the cloud secret store
     * ({@link TlsCertStore#downloadCommand}) and, if present, reconstruct a
     * clean TLS secret and apply it before cert-manager reconciles the Ingress.
     * cert-manager then adopts the existing valid cert and skips issuance (it
     * still auto-renews near expiry). Best effort — any failure falls through to
     * a normal Let's Encrypt request.
     */
    private String restoreTlsSecretCommand() {
        String staging = certStagingFile();
        return format("""
            %4$s
            if [ -s %3$s ]; then
              CRT=$(cut -d' ' -f1 %3$s); KEY=$(cut -d' ' -f2 %3$s)
              rm -f %3$s
              if [ -n "$CRT" ] && [ -n "$KEY" ]; then
                echo "Restoring TLS secret %1$s from the cloud secret store"
                printf 'apiVersion: v1\\nkind: Secret\\nmetadata:\\n  name: %1$s\\n  namespace: %2$s\\ntype: kubernetes.io/tls\\ndata:\\n  tls.crt: %%s\\n  tls.key: %%s\\n' "$CRT" "$KEY" | kubectl apply -f - || echo "TLS restore failed; cert-manager will request a new cert"
              else
                echo "Stored TLS blob for %1$s incomplete; cert-manager will request a new cert"
              fi
            else
              echo "No stored TLS cert for %1$s; cert-manager will request a new cert"
            fi""", tlsSecretName, TLS_SECRET_NAMESPACE, staging,
            certStore.downloadCommand(tlsSecretName, staging));
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
        return IngressV1.Builder
            .create(construct, POLARIS_SERVICE_NAME)
            .metadata(IngressV1Metadata
                          .builder()
                          .name(POLARIS_SERVICE_NAME)
                          .annotations(Map.of(
                              "cert-manager.io/cluster-issuer", "letsencrypt-prod"
                                             ))
                          .build())
            .dependsOn(dependencies)
            // Back up the issued cert before the cluster is torn down so the
            // next create can reuse it instead of asking Let's Encrypt again.
            .provisioners(uponDestroyRun(backupTlsSecretCommand(), PolarisConfig.NO_POLARIS_FINALIZERS))
            .spec(nginxSpec)
            .waitForLoadBalancer(true)
            .build();
    }

    public Release installNginxIngress(Construct construct,
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
            // Restore a previously-issued cert (if any) before the Ingress is
            // created, so cert-manager adopts it rather than requesting a new
            // one. Runs ahead of the ClusterIssuer apply; both are idempotent.
            .provisioners(CdkUtils.uponCreateRun(restoreTlsSecretCommand(), clusterIssuerYaml))
            .build();
    }
}
