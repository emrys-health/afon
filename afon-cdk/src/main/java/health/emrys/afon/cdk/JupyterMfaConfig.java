package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.deployment.*;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import health.emrys.afon.config.AfonPropertyProvider;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.config.AfonProperty.OIDC_EMAIL_DOMAIN;
import static health.emrys.afon.config.AfonProperty.OIDC_ISSUER_URL;
import static java.lang.String.format;

/**
 * Deploys oauth2-proxy in front of Jupyter to enforce MFA via an OIDC identity provider.
 *
 * <p>Traffic flow:
 * <pre>
 *   AWS LoadBalancer (port 80) ─► oauth2-proxy pod (port 4180) ─► jupyter-web ClusterIP (port 8888) ─► Jupyter pod
 * </pre>
 *
 * <p>oauth2-proxy delegates authentication to an OIDC provider (default: Google). MFA is enforced
 * by the provider itself — any account with 2-Step Verification enabled will require it.
 */
public class JupyterMfaConfig {

    private static final Map<String, String> PROXY_LABELS = Map.of("app", CdkHelper.PROXY_SERVICE_NAME);
    private static final String              SECRET_NAME  = CdkHelper.PROXY_SERVICE_NAME +"-secret";
    /** oauth2-proxy answers this itself, unauthenticated, so the load balancer can health check it. */
    public static final String               PING_PATH    = "/ping";

    public JupyterMfaConfig(Construct scope,
                     List<ITerraformDependable> dependsOn,
                     AfonPropertyProvider properties,
                     String jupyterUpstreamUrl,
                     String jupyterDomain,
                     String clientId,
                     String clientSecret,
                     String cookieSecret) {
        this(scope, dependsOn, properties, List.of(jupyterUpstreamUrl), jupyterDomain, clientId, clientSecret, cookieSecret);
    }

    /**
     * Variant accepting several upstreams. oauth2-proxy mounts each upstream at the path component
     * of its URL (longest-prefix match), so a single MFA gate can front several apps on one domain
     * — e.g. JupyterHub at {@code /} and Guacamole at {@code /guacamole/}.
     */
    public JupyterMfaConfig(Construct scope,
                     List<ITerraformDependable> dependsOn,
                     AfonPropertyProvider properties,
                     List<String> upstreamUrls,
                     String jupyterDomain,
                     String clientId,
                     String clientSecret,
                     String cookieSecret) {
        Secret oauthSecret = Secret.Builder
            .create(scope, "oauth2ProxySecret")
            .metadata(SecretMetadata
                          .builder()
                          .name(SECRET_NAME)
                          .namespace("default")
                          .build())
            .data(Map.of(
                "client-id", clientId,
                "client-secret", clientSecret,
                "cookie-secret", cookieSecret
                        ))
            .build();

        String issuer = properties.getValue(OIDC_ISSUER_URL);

        List<String> proxyArgs = new ArrayList<>(List.of(
            "--provider=oidc",
            "--oidc-issuer-url=" + issuer,
            "--email-domain=" + properties.getValue(OIDC_EMAIL_DOMAIN),
            "--http-address=0.0.0.0:" + CdkHelper.PROXY_PORT,
            "--cookie-secure=true",                           // set true when TLS terminates at this proxy
            // Accept id_tokens whose email is unverified: this Keycloak has no SMTP verification flow,
            // so users are provisioned with emailVerified=true, but manually-created users may not be.
            "--insecure-oidc-allow-unverified-email=true",
            // Forward the authenticated identity to upstreams as X-Forwarded-Email. Guacamole's
            // header-auth extension reads this to log the user in with no second login page.
            "--pass-user-headers=true",
            "--skip-provider-button=false",
            "--ping-path=" + PING_PATH,
            format("--redirect-url=https://%s/oauth2/callback", jupyterDomain)));
        // One --upstream per app; oauth2-proxy routes by the path component of each URL.
        upstreamUrls.forEach(url -> proxyArgs.add("--upstream=" + url));

        DeploymentSpecTemplateSpecContainer proxyContainer = DeploymentSpecTemplateSpecContainer
            .builder()
            .name("oauth2-proxy")
            .image(DockerImages.OAUTH2_PROXY_IMAGE)
            .args(proxyArgs)
            .env(List.of(
                envFromSecret("OAUTH2_PROXY_CLIENT_ID", SECRET_NAME, "client-id"),
                envFromSecret("OAUTH2_PROXY_CLIENT_SECRET", SECRET_NAME, "client-secret"),
                envFromSecret("OAUTH2_PROXY_COOKIE_SECRET", SECRET_NAME, "cookie-secret")
                        ))
            .build();

        List<ITerraformDependable> deps = new ArrayList<>(dependsOn);
        deps.add(oauthSecret);

        CdkUtils.deploy(scope, deps, PROXY_LABELS, List.of(proxyContainer),
                               List.of(waitForIssuer(issuer)), List.of(), "oauth2-proxy", "15m");

        Service.Builder.create(scope, CdkHelper.PROXY_SERVICE_NAME)
                       .metadata(ServiceMetadata
                                     .builder()
                                     .name(CdkHelper.PROXY_SERVICE_NAME)
                                     .namespace("default")
                                     .build())
                       .dependsOn(deps)
                       .spec(ServiceSpec.builder()
                                        .selector(PROXY_LABELS)
                                        .type("NodePort")
                                        .port(List.of(ServiceSpecPort
                                                          .builder()
                                                          .name("http")
                                                          .port(CdkHelper.PROXY_PORT)
                                                          .targetPort(String.valueOf(CdkHelper.PROXY_PORT))
                                                          .protocol("TCP")
                                                          .build()))
                                        .build())
                       .build();
    }

    /**
     * Blocks until the OIDC issuer serves its discovery document.
     *
     * <p>oauth2-proxy fetches that document once, at startup, and exits if it cannot. The issuer is a
     * public, load-balancer-backed hostname that does not resolve until the LB is provisioned and its
     * DNS record propagates — minutes after this pod is first scheduled. Without this gate the proxy
     * crashes, and once CrashLoopBackOff reaches its five-minute cap the pod sits idle long past the
     * point the issuer became reachable, overrunning Terraform's rollout wait.
     *
     * <p>Unlike JupyterHub — whose config is Python we can make retry in process — oauth2-proxy is a
     * binary, so the wait has to live beside it. Curl's {@code -f} also treats the load balancer's
     * "no healthy targets" 5xx as a failure, so this waits for Keycloak to actually serve, not merely
     * for the name to resolve. Deliberately unbounded: if the issuer never comes up, the pod stays in
     * {@code Init:0/1} with this container's log naming exactly what it is waiting for, which is a far
     * clearer failure than a crash loop.
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
            .image(DockerImages.CURL_IMAGE)
            .command(List.of("sh", "-c", script))
            .env(List.of(DeploymentSpecTemplateSpecInitContainerEnv
                             .builder()
                             .name("DISCOVERY_URL")
                             .value(discoveryUrl)
                             .build()))
            .build();
    }

    private static DeploymentSpecTemplateSpecContainerEnv envFromSecret(
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
}
