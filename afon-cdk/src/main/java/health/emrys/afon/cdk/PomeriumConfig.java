package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.config_map.ConfigMap;
import com.hashicorp.cdktf.providers.kubernetes.config_map.ConfigMapMetadata;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainer;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerVolumeMount;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecInitContainer;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecInitContainerEnv;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecVolume;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecVolumeConfigMap;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.cdk.CdkHelper.clusterIpService;
import static health.emrys.afon.cdk.CdkHelper.nodePortService;
import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.security.keycloak.KeyCloakConfigurer.KEYCLOAK_REALM;
import static java.lang.String.format;

/**
 * Deploys Pomerium Core (all-in-one) as a zero-trust access proxy in front of the
 * {@code pomerium/verify} demo app, authenticating against the existing KeyCloak
 * (realm {@value health.emrys.afon.security.keycloak.KeyCloakConfigurer#KEYCLOAK_REALM}).
 * See docs/pomerium-zero-trust-plan.md.
 *
 * <p>Traffic flow (TLS terminates at the cloud load balancer, so Pomerium runs
 * {@code insecure_server: true} like every other pod here):
 * <pre>
 *   Internet ──► LB :443 ──► pomerium :8080 ──► pomerium-verify :8000 (ClusterIP, no ingress of its own)
 *                                │
 *                                └─ OIDC ──► KeyCloak realm "polaris"
 * </pre>
 *
 * <p>The example policy allows only members of the {@link health.emrys.afon.config.AfonProperties#POMERIUM_ALLOWED_GROUP}
 * KeyCloak group (default {@code cluster-admins}); members of {@code analysts} authenticate
 * successfully but are denied — demonstrating per-request, identity-based least privilege.
 *
 * <p>Cloud-specific HTTPS/DNS wiring (both the authenticate host and the verify host must resolve
 * to the same load balancer) is left to the cloud config, e.g. {@code AwsPomeriumConfig}.
 */
public class PomeriumConfig {

    public static final int    POMERIUM_PORT     = 8080;
    public static final String POMERIUM_SVC      = "pomerium";
    public static final String POMERIUM_INGRESS  = "pomerium-ingress";
    public static final String POMERIUM_IMAGE    = "pomerium/pomerium:v0.30.0";

    public static final int    VERIFY_PORT = 8000;
    public static final String VERIFY_SVC  = "pomerium-verify";

    private static final Map<String, String> POMERIUM_LABELS = Map.of("app", "pomerium");
    private static final Map<String, String> VERIFY_LABELS   = Map.of("app", VERIFY_SVC);
    private static final String              SECRET_NAME     = "pomerium-secrets";
    private static final String              CONFIG_MAP_NAME = "pomerium-config";
    private static final String              CONFIG_PATH     = "/pomerium";

    private final String authenticateHost;
    private final String verifyHost;

    public PomeriumConfig(Construct scope,
                          List<ITerraformDependable> dependsOn,
                          Properties properties) {
        this.authenticateHost = POMERIUM_AUTHENTICATE_HOSTNAME.getValueFrom(properties);
        this.verifyHost       = POMERIUM_VERIFY_HOSTNAME.getValueFrom(properties);
        String allowedGroup   = POMERIUM_ALLOWED_GROUP.getValueFrom(properties, "cluster-admins");
        String issuer         = format(
            "https://%s/realms/%s", KC_HOSTNAME.getValueFrom(properties), KEYCLOAK_REALM);

        // Secrets are passed as environment variables (which override the config file in Pomerium)
        // so the config itself can live in a plain ConfigMap.
        Secret pomeriumSecrets = Secret.Builder
            .create(scope, "pomeriumSecrets")
            .metadata(SecretMetadata
                          .builder()
                          .name(SECRET_NAME)
                          .namespace("default")
                          .build())
            .data(Map.of(
                "shared-secret", POMERIUM_SHARED_SECRET.getValueFrom(properties),
                "cookie-secret", POMERIUM_COOKIE_SECRET.getValueFrom(properties),
                "idp-client-id", POMERIUM_CLIENT_ID.getValueFrom(properties),
                "idp-client-secret", POMERIUM_CLIENT_SECRET.getValueFrom(properties)))
            .dependsOn(dependsOn)
            .build();

        ConfigMap pomeriumConfig = ConfigMap.Builder
            .create(scope, "pomeriumConfigMap")
            .metadata(ConfigMapMetadata
                          .builder()
                          .name(CONFIG_MAP_NAME)
                          .namespace("default")
                          .build())
            .data(Map.of("config.yaml", configYaml(issuer, allowedGroup)))
            .dependsOn(dependsOn)
            .build();

        // The protected upstream: reachable ONLY through Pomerium (ClusterIP, no ingress). The
        // verify app renders the caller's identity and the signed X-Pomerium-Jwt-Assertion it
        // received, making the zero-trust handshake visible.
        DeploymentSpecTemplateSpecContainer verifyContainer = DeploymentSpecTemplateSpecContainer
            .builder()
            .name(VERIFY_SVC)
            .image("pomerium/verify:latest")
            .build();
        CdkUtils.deploy(scope, dependsOn, VERIFY_LABELS, List.of(verifyContainer), List.of(), VERIFY_SVC);
        List<ServiceSpecPort> verifyPorts = List.of(ServiceSpecPort
                                                        .builder()
                                                        .name("http")
                                                        .port(VERIFY_PORT)
                                                        .targetPort(String.valueOf(VERIFY_PORT))
                                                        .protocol("TCP")
                                                        .build());
        clusterIpService(dependsOn, VERIFY_LABELS, VERIFY_SVC, verifyPorts, scope);

        DeploymentSpecTemplateSpecContainer pomerium = DeploymentSpecTemplateSpecContainer
            .builder()
            .name("pomerium")
            .image(POMERIUM_IMAGE)
            .args(List.of("-config", CONFIG_PATH + "/config.yaml"))
            .env(List.of(
                CdkHelper.envFromSecret("SHARED_SECRET", SECRET_NAME, "shared-secret"),
                CdkHelper.envFromSecret("COOKIE_SECRET", SECRET_NAME, "cookie-secret"),
                CdkHelper.envFromSecret("IDP_CLIENT_ID", SECRET_NAME, "idp-client-id"),
                CdkHelper.envFromSecret("IDP_CLIENT_SECRET", SECRET_NAME, "idp-client-secret")))
            .volumeMount(List.of(DeploymentSpecTemplateSpecContainerVolumeMount
                                     .builder()
                                     .name("config")
                                     .mountPath(CONFIG_PATH)
                                     .readOnly(true)
                                     .build()))
            .build();
        DeploymentSpecTemplateSpecVolume configVolume = DeploymentSpecTemplateSpecVolume
            .builder()
            .name("config")
            .configMap(DeploymentSpecTemplateSpecVolumeConfigMap
                           .builder()
                           .name(CONFIG_MAP_NAME)
                           .build())
            .build();

        List<ITerraformDependable> deps = new ArrayList<>(dependsOn);
        deps.add(pomeriumSecrets);
        deps.add(pomeriumConfig);
        // Same rationale as oauth2-proxy (JupyterMfaConfig): Pomerium performs OIDC discovery at
        // startup, and the issuer is a load-balancer-backed hostname that may not resolve yet.
        CdkUtils.deploy(scope, deps, POMERIUM_LABELS, List.of(pomerium),
                        List.of(waitForIssuer(issuer)), List.of(configVolume), "pomerium", "15m");

        // NodePort (not ClusterIP): on AWS the Load Balancer Controller uses instance targets by
        // default and requires a node port to build the ingress TargetGroup.
        List<ServiceSpecPort> ports = List.of(ServiceSpecPort
                                                  .builder()
                                                  .name("http")
                                                  .port(POMERIUM_PORT)
                                                  .targetPort(String.valueOf(POMERIUM_PORT))
                                                  .protocol("TCP")
                                                  .build());
        nodePortService(deps, POMERIUM_LABELS, POMERIUM_SVC, ports, scope);
    }

    /**
     * Public host of Pomerium's authenticate service (the OIDC login endpoint).
     */
    public String getAuthenticateHost() {
        return authenticateHost;
    }

    /**
     * Public host of the protected verify route.
     */
    public String getVerifyHost() {
        return verifyHost;
    }

    /**
     * Pomerium's all-in-one configuration. The example route protects the verify app with a
     * Pomerium Policy Language (PPL) rule that only allows members of {@code allowedGroup} — the
     * {@code groups} claim is stamped into the id_token by the KeyCloak group-membership mapper
     * that KeyCloakProvider#registerPomeriumClient attaches to the Pomerium client.
     * Secrets (shared_secret, cookie_secret, idp_client_id, idp_client_secret) arrive via
     * environment variables, which Pomerium gives precedence over this file.
     */
    private String configYaml(String issuer, String allowedGroup) {
        return format(
            """
            address: :%d
            insecure_server: true
            authenticate_service_url: https://%s
            idp_provider: oidc
            idp_provider_url: %s
            routes:
              - from: https://%s
                to: http://%s:%d
                pass_identity_headers: true
                policy:
                  allow:
                    and:
                      - claim/groups: %s
            """,
            POMERIUM_PORT, authenticateHost, issuer, verifyHost, VERIFY_SVC, VERIFY_PORT, allowedGroup);
    }

    /**
     * Blocks until the OIDC issuer serves its discovery document — the same guard oauth2-proxy
     * needs (see JupyterMfaConfig#waitForIssuer): Pomerium fetches the document at startup and the
     * issuer hostname only resolves once the load balancer and its DNS record exist.
     */
    private static DeploymentSpecTemplateSpecInitContainer waitForIssuer(String issuer) {
        String discoveryUrl = issuer.replaceAll("/+$", "") + "/.well-known/openid-configuration";
        String script = String.join("\n",
            "until curl -sfo /dev/null \"$DISCOVERY_URL\"; do",
            "  echo \"waiting for OIDC issuer $DISCOVERY_URL\"",
            "  sleep 5",
            "done",
            "echo \"OIDC issuer reachable\"");
        return DeploymentSpecTemplateSpecInitContainer
            .builder()
            .name("wait-for-issuer")
            .image("curlimages/curl:8.11.1")
            .command(List.of("sh", "-c", script))
            .env(List.of(DeploymentSpecTemplateSpecInitContainerEnv
                             .builder()
                             .name("DISCOVERY_URL")
                             .value(discoveryUrl)
                             .build()))
            .build();
    }
}
