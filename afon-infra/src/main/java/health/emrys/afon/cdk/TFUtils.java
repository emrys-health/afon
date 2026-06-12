package health.emrys.afon.cdk;

import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.TerraformResource;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.helm.release.ReleaseSet;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerEnv;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.*;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.infra.Defaults.POLARIS_INGRESS_NAME;
import static java.lang.String.format;

public class TFUtils {

    /**
     * Where issued TLS certs are cached between {@code tofu destroy}/{@code apply} cycles so
     * that redeploys reuse an existing Let's Encrypt certificate instead of requesting a new
     * one. Re-requesting on every redeploy quickly hits LE's production limit of 5 certs per
     * week per exact set of identifiers.
     */
    private static final String TLS_BACKUP_DIR = "$HOME/.afon/tls";

    public static final List<LocalExecProvisioner> POLARIS_PROVISIONERS
        = uponDestroyRun(PolarisConfig.NO_POLARIS_FINALIZERS);

    public static DeploymentSpecTemplateSpecContainerEnv envVar(String name, String value) {
        return DeploymentSpecTemplateSpecContainerEnv.builder()
                                                     .name(name)
                                                     .value(value)
                                                     .build();
    }

    public static Release installNginxIngress(Construct construct,
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

    private final String    domainName;
    private final String    tlsSecretName;
    private final int       port;
    private final String    internalService;
    private final Construct construct;
    private final String    ingressName;

    public TFUtils(Construct construct,
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

    public IngressV1 createIngress(List<TerraformResource> dependencies,
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

        // Restore a cached cert (if any) before the ClusterIssuer/ingress trigger issuance,
        // and back the issued cert up on teardown — see TLS_BACKUP_DIR.
        List<LocalExecProvisioner> provisioners = new ArrayList<>();
        provisioners.addAll(CdkUtils.uponCreateRun(restoreTlsSecretCommand()));
        provisioners.addAll(CdkUtils.uponCreateRun(clusterIssuerYaml));
        provisioners.addAll(uponDestroyRun(backupTlsSecretCommand()));

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
            .provisioners(provisioners)
            .build();
    }

    private String tlsBackupFile() {
        return format("%s/%s-%s.yaml", TLS_BACKUP_DIR, domainName, tlsSecretName);
    }

    /**
     * Restores the cached TLS secret into the {@code default} namespace if a backup exists, so
     * cert-manager sees a valid certificate already present and skips the ACME request. Always
     * exits 0 — a missing or unusable backup must not fail {@code apply}; cert-manager simply
     * issues a fresh certificate in that case.
     */
    private String restoreTlsSecretCommand() {
        return format(
            "BK=\"%1$s\"; " +
            "if [ -f \"$BK\" ]; then " +
            "echo \"Restoring TLS secret %2$s from $BK to avoid re-requesting a certificate\"; " +
            "kubectl apply -n default -f \"$BK\" || echo \"TLS restore failed; cert-manager will issue a new certificate\"; " +
            "else echo \"No TLS backup at $BK; cert-manager will issue a new certificate\"; fi",
            tlsBackupFile(), tlsSecretName);
    }

    /**
     * On teardown, caches the live TLS secret to {@link #TLS_BACKUP_DIR} — but only if it holds a
     * genuine Let's Encrypt certificate with at least 30 days left (longer than cert-manager's
     * default renewBefore), so the restored cert won't immediately trigger a renewal. The nginx
     * "Fake Certificate" and near-expiry certs are skipped so a good backup is never clobbered.
     */
    private String backupTlsSecretCommand() {
        return format(
            "mkdir -p \"%1$s\"; BK=\"%2$s\"; C=$(mktemp); K=$(mktemp); " +
            "kubectl get secret %3$s -n default -o jsonpath='{.data.tls\\.crt}' 2>/dev/null | base64 -d > \"$C\"; " +
            "kubectl get secret %3$s -n default -o jsonpath='{.data.tls\\.key}' 2>/dev/null | base64 -d > \"$K\"; " +
            "if [ -s \"$C\" ] && openssl x509 -in \"$C\" -noout -issuer 2>/dev/null | grep -qi \"let's encrypt\" " +
            "&& openssl x509 -in \"$C\" -noout -checkend 2592000 >/dev/null 2>&1; then " +
            "kubectl create secret tls %3$s -n default --cert=\"$C\" --key=\"$K\" --dry-run=client -o yaml > \"$BK\"; " +
            "echo \"Backed up Let's Encrypt cert for %4$s to $BK\"; " +
            "else echo \"No renewable Let's Encrypt cert in %3$s; keeping any existing backup\"; fi; " +
            "rm -f \"$C\" \"$K\"",
            TLS_BACKUP_DIR, tlsBackupFile(), tlsSecretName, domainName);
    }
}
