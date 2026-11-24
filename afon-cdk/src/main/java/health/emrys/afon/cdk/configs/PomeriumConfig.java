package health.emrys.afon.cdk.configs;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.deployment.*;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import health.emrys.afon.cdk.CdkHelper;
import health.emrys.afon.cdk.CdkUtils;
import health.emrys.afon.cdk.DockerImageResolver;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import software.constructs.Construct;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deploys Pomerium Core in front of JupyterHub and Guacamole to enforce MFA (via the OIDC identity
 * provider) and per-request route policies.
 *
 * <p>Traffic flow, see {@link JupyterHubConfig#MFA_UPSTREAMS}:
 * <pre>
 *   HTTPS ingress (jupyter.<env>, auth.jupyter.<env>)
 *       ─► pomerium pod (port 8080) ─┬─► proxy-public ClusterIP (port 8000) ─► JupyterHub / user pods
 *                                    └─► guacamole-web ClusterIP (port 8080) ─► Guacamole
 * </pre>
 *
 * <p>Pomerium delegates authentication to the OIDC issuer, the cloud's Keycloak
 * ({@code OIDC_ISSUER_URL}), whose browser flow requires MFA, and evaluates the route's PPL policy
 * (the TRE's member list) on every request.
 */
public class PomeriumConfig {

    /** The labels of the Pomerium pods, the only ones that may reach Guacamole, see {@link GuacamoleConfig}. */
    static final Map<String, String> PROXY_LABELS = Map.of("app", CdkHelper.PROXY_SERVICE_NAME);

    /** Pomerium answers this itself, unauthenticated, so the load balancer and kubelet can health check it. */
    public static final String PING_PATH = "/ping";

    /** The header Pomerium sets from the {@code email} claim on the Guacamole route, trusted by guacamole-auth-header. */
    public static final String IDENTITY_HEADER = "X-Pomerium-Claim-Email";

    /**
     * Session cookie lifetime, kept equal to {@code KeyCloakProvider.SSO_SESSION_LIFESPAN_SECONDS} (12h) so
     * that a user's Pomerium session does not outlive Keycloak's SSO session.
     */
    public static final String COOKIE_EXPIRE = "12h";

    /** Kubernetes Secret holding Pomerium's {@code config.yaml}, mounted at {@value #CONFIG_MOUNT_PATH}. */
    static final String SECRET_NAME       = "pomerium-config";
    static final String CONFIG_KEY        = "config.yaml";
    static final String CONFIG_MOUNT_PATH = "/pomerium";

    /**
     * Regex matching {@code /hub/admin} and its variants (uppercase, repeated slashes, percent-encoded slashes,
     * matrix parameters) so the no-policy deny route catches them before the {@code /} route.
     */
    static final String ADMIN_PATH_REGEX = "(?i)^/+hub(?:/|%2f)+admin(?:[/;%].*)?$";

    /**
     * @param upstreamUrls the apps the gate fronts (see {@link JupyterHubConfig#MFA_UPSTREAMS}): Guacamole at
     *                     {@code /guacamole/} and JupyterHub at {@code /}
     * @param members      the TRE's member email addresses permitted by the PPL route policies
     */
    public PomeriumConfig(Construct scope,
                          List<ITerraformDependable> dependsOn,
                          DockerImageResolver images,
                          List<String> upstreamUrls,
                          String issuer,
                          String jupyterDomain,
                          String clientId,
                          String clientSecret,
                          String sharedSecret,
                          String cookieSecret,
                          List<String> members) {
        String configYaml = configYaml(
            upstreamUrls, issuer, jupyterDomain, clientId, clientSecret, sharedSecret, cookieSecret, members);

        Secret configSecret = Secret.Builder
            .create(scope, "pomeriumConfigSecret")
            .metadata(SecretMetadata.builder()
                                    .name(SECRET_NAME)
                                    .namespace("default")
                                    .build())
            .data(Map.of(CONFIG_KEY, configYaml))
            .build();

        DeploymentSpecTemplateSpecContainerReadinessProbe readinessProbe =
            DeploymentSpecTemplateSpecContainerReadinessProbe.builder()
                .httpGet(DeploymentSpecTemplateSpecContainerReadinessProbeHttpGet.builder()
                             .path(PING_PATH)
                             .port(String.valueOf(CdkHelper.PROXY_PORT))
                             .build())
                .build();

        DeploymentSpecTemplateSpecContainerLivenessProbe livenessProbe =
            DeploymentSpecTemplateSpecContainerLivenessProbe.builder()
                .httpGet(DeploymentSpecTemplateSpecContainerLivenessProbeHttpGet.builder()
                             .path(PING_PATH)
                             .port(String.valueOf(CdkHelper.PROXY_PORT))
                             .build())
                .build();

        DeploymentSpecTemplateSpecContainerResources resources =
            DeploymentSpecTemplateSpecContainerResources.builder()
                .requests(Map.of("cpu", "100m", "memory", "256Mi"))
                .limits(Map.of("memory", "512Mi"))
                .build();

        DeploymentSpecTemplateSpecContainer proxyContainer = DeploymentSpecTemplateSpecContainer
            .builder()
            .name(CdkHelper.PROXY_SERVICE_NAME)
            .image(images.pomerium())
            .port(List.of(DeploymentSpecTemplateSpecContainerPort.builder()
                              .name("http")
                              .containerPort(CdkHelper.PROXY_PORT)
                              .protocol("TCP")
                              .build()))
            // Mounted without subPath so that Secret updates (e.g. a changed member list) propagate to
            // the running pod and Pomerium hot-reloads /pomerium/config.yaml without a restart.
            .volumeMount(List.of(DeploymentSpecTemplateSpecContainerVolumeMount.builder()
                                     .name(SECRET_NAME)
                                     .mountPath(CONFIG_MOUNT_PATH)
                                     .readOnly(true)
                                     .build()))
            .readinessProbe(readinessProbe)
            .livenessProbe(livenessProbe)
            .resources(resources)
            .build();

        DeploymentSpecTemplateSpecVolume configVolume = DeploymentSpecTemplateSpecVolume
            .builder()
            .name(SECRET_NAME)
            .secret(DeploymentSpecTemplateSpecVolumeSecret.builder()
                        .secretName(SECRET_NAME)
                        .build())
            .build();

        List<ITerraformDependable> deps = new ArrayList<>(dependsOn);
        deps.add(configSecret);

        CdkUtils.deploy(scope, deps, PROXY_LABELS, List.of(proxyContainer),
                        List.of(waitForIssuer(issuer, images.curl())), List.of(configVolume),
                        CdkHelper.PROXY_SERVICE_NAME, "15m", null, Map.of(),
                        DeploymentSpecStrategy.builder().type("Recreate").build());

        Service.Builder.create(scope, CdkHelper.PROXY_SERVICE_NAME)
                       .metadata(ServiceMetadata.builder()
                                                .name(CdkHelper.PROXY_SERVICE_NAME)
                                                .namespace("default")
                                                .build())
                       .dependsOn(deps)
                       .spec(ServiceSpec.builder()
                                        .selector(PROXY_LABELS)
                                        .type("NodePort")
                                        .port(List.of(ServiceSpecPort.builder()
                                                          .name("http")
                                                          .port(CdkHelper.PROXY_PORT)
                                                          .targetPort(String.valueOf(CdkHelper.PROXY_PORT))
                                                          .protocol("TCP")
                                                          .build()))
                                        .build())
                       .build();
    }

    /**
     * Renders Pomerium's {@code config.yaml} in deterministic block-style YAML.
     *
     * <p>Routes are ordered specifically:
     * <ol>
     *   <li>{@value #ADMIN_PATH_REGEX} with no policy — refuses {@code /hub/admin} (and its variants) to everyone.</li>
     *   <li>{@code /guacamole/} with {@code pass_identity_headers: true} — forwards {@value #IDENTITY_HEADER} to Guacamole.</li>
     *   <li>{@code /} — fronts JupyterHub without identity headers.</li>
     * </ol>
     * When {@code members} is empty, the {@code policy} key is omitted on the member routes so Pomerium refuses
     * everyone rather than emitting an empty {@code or: []} rule.
     */
    static String configYaml(List<String> upstreamUrls,
                             String issuer,
                             String jupyterDomain,
                             String clientId,
                             String clientSecret,
                             String sharedSecret,
                             String cookieSecret,
                             List<String> members) {
        String guacamoleUpstream = upstreamForPath(upstreamUrls, JupyterHubConfig.GUACAMOLE_PATH);
        String jupyterUpstream   = upstreamForPath(upstreamUrls, JupyterHubConfig.HUB_BASE_URL);
        String fromUrl           = "https://" + jupyterDomain;

        List<Map<String, Object>> memberPolicy = memberPolicy(members);

        Map<String, Object> adminRoute = new LinkedHashMap<>();
        adminRoute.put("from", fromUrl);
        adminRoute.put("regex", ADMIN_PATH_REGEX);
        adminRoute.put("to", jupyterUpstream);
        adminRoute.put("preserve_host_header", true);

        Map<String, Object> guacamoleRoute = new LinkedHashMap<>();
        guacamoleRoute.put("from", fromUrl);
        guacamoleRoute.put("prefix", JupyterHubConfig.GUACAMOLE_PATH);
        guacamoleRoute.put("to", guacamoleUpstream);
        guacamoleRoute.put("allow_websockets", true);
        guacamoleRoute.put("pass_identity_headers", true);
        guacamoleRoute.put("preserve_host_header", true);
        if (!memberPolicy.isEmpty()) {
            guacamoleRoute.put("policy", memberPolicy);
        }

        Map<String, Object> jupyterRoute = new LinkedHashMap<>();
        jupyterRoute.put("from", fromUrl);
        jupyterRoute.put("prefix", JupyterHubConfig.HUB_BASE_URL);
        jupyterRoute.put("to", jupyterUpstream);
        jupyterRoute.put("allow_websockets", true);
        jupyterRoute.put("preserve_host_header", true);
        if (!memberPolicy.isEmpty()) {
            jupyterRoute.put("policy", memberPolicy);
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("address", ":" + CdkHelper.PROXY_PORT);
        config.put("insecure_server", true);
        config.put("authenticate_service_url", "https://auth." + jupyterDomain);
        config.put("idp_provider", "oidc");
        config.put("idp_provider_url", issuer);
        config.put("idp_client_id", clientId);
        config.put("idp_client_secret", clientSecret);
        config.put("idp_scopes", List.of("openid", "email", "profile"));
        config.put("shared_secret", sharedSecret);
        config.put("cookie_secret", cookieSecret);
        config.put("cookie_expire", COOKIE_EXPIRE);
        config.put("xff_num_trusted_hops", 1);
        Map<String, String> claimsHeaders = new LinkedHashMap<>();
        claimsHeaders.put(IDENTITY_HEADER, "email");
        config.put("jwt_claims_headers", claimsHeaders);
        config.put("routes", List.of(adminRoute, guacamoleRoute, jupyterRoute));

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setWidth(Integer.MAX_VALUE);
        return new Yaml(options).dump(config);
    }

    private static List<Map<String, Object>> memberPolicy(List<String> members) {
        List<Map<String, Object>> emailRules = members.stream()
            .map(email -> email.trim().toLowerCase(Locale.ROOT))
            .filter(email -> !email.isEmpty())
            .distinct()
            .sorted()
            .map(email -> {
                Map<String, Object> isEmail = new LinkedHashMap<>();
                isEmail.put("is", email);
                Map<String, Object> rule = new LinkedHashMap<>();
                rule.put("email", isEmail);
                return rule;
            })
            .toList();
        if (emailRules.isEmpty()) {
            return List.of();
        }
        Map<String, Object> or = new LinkedHashMap<>();
        or.put("or", emailRules);
        Map<String, Object> allow = new LinkedHashMap<>();
        allow.put("allow", or);
        return List.of(allow);
    }

    private static String upstreamForPath(List<String> upstreamUrls, String expectedPath) {
        return upstreamUrls.stream()
            .filter(url -> expectedPath.equals(URI.create(url).getPath()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "No upstream with path '" + expectedPath + "' in " + upstreamUrls));
    }

    /**
     * Blocks until the OIDC issuer serves its discovery document.
     *
     * <p>Pomerium fetches that document at startup. The issuer is a public, load-balancer-backed
     * hostname that does not resolve until the LB is provisioned and its DNS record propagates —
     * minutes after this pod is first scheduled. Waiting here avoids a CrashLoopBackOff while
     * Keycloak is still coming up. Curl's {@code -f} treats the load balancer's "no healthy targets"
     * 5xx as a failure, so this waits for Keycloak to actually serve, not merely for the name to
     * resolve.
     */
    private static DeploymentSpecTemplateSpecInitContainer waitForIssuer(String issuer, String curlImage) {
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
            .image(curlImage)
            .command(List.of("sh", "-c", script))
            .env(List.of(DeploymentSpecTemplateSpecInitContainerEnv
                             .builder()
                             .name("DISCOVERY_URL")
                             .value(discoveryUrl)
                             .build()))
            .build();
    }
}
