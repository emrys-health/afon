package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainer;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecInitContainer;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecInitContainerEnv;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import health.emrys.afon.config.AfonPropertiesLoader;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.cdk.TFUtils.envFromSecret;
import static health.emrys.afon.config.AfonProperties.OIDC_EMAIL_DOMAIN;
import static health.emrys.afon.config.AfonProperties.OIDC_ISSUER_URL;
import static java.lang.String.format;

/**
 * Deploys oauth2-proxy in front of an arbitrary upstream to enforce MFA via an
 * OIDC identity provider. Shared by every workbench (Jupyter via
 * {@link JupyterMfaConfig}, VS Code, RStudio) — the workbench only differs in
 * its upstream URL and names.
 *
 * <p>Traffic flow:
 * <pre>
 *   cloud LB / ingress ─► oauth2-proxy pod (port 4180) ─► upstream ClusterIP ─► workbench pod
 * </pre>
 *
 * <p>oauth2-proxy delegates authentication to an OIDC provider (default: Google). MFA is enforced
 * by the provider itself — any account with 2-Step Verification enabled will require it.
 */
public class AuthProxyConfig {

    public static final int PROXY_PORT = 4180;

    /**
     * Kubernetes/construct names for one proxy instance, so several proxies can
     * coexist in a stack. {@link JupyterMfaConfig} pins the legacy names.
     *
     * @param proxyName         Deployment/Service/container name and {@code app} label
     * @param secretConstructId construct id of the client-credentials Secret
     * @param secretName        Kubernetes name of the client-credentials Secret
     */
    public record Names(String proxyName, String secretConstructId, String secretName) {

        /** Conventional names derived from the proxy name, e.g. "vscode-oauth2-proxy". */
        public static Names of(String proxyName) {
            return new Names(proxyName, proxyName + "Secret", proxyName + "-secret");
        }
    }

    private final String proxyName;

    /**
     * @param upstreamUrl the workbench's in-cluster URL the proxy forwards
     *                    authenticated traffic to
     * @param domain      the public domain the workbench is served on; used for
     *                    the OAuth callback URL
     * @param emailDomain restricts sign-in to this email domain; {@code null}
     *                    falls back to the {@code OIDC_EMAIL_DOMAIN} property
     */
    public AuthProxyConfig(Construct scope,
                           List<ITerraformDependable> dependsOn,
                           Names names,
                           String upstreamUrl,
                           String domain,
                           String clientId,
                           String clientSecret,
                           String cookieSecret,
                           String emailDomain) {
        this(scope, dependsOn, names, List.of(upstreamUrl), domain,
             clientId, clientSecret, cookieSecret, emailDomain);
    }

    /**
     * Variant accepting several upstreams. oauth2-proxy mounts each upstream at the path component
     * of its URL (longest-prefix match), so a single MFA gate can front several apps on one domain
     * — e.g. JupyterHub at {@code /} and Guacamole at {@code /guacamole/}.
     */
    public AuthProxyConfig(Construct scope,
                           List<ITerraformDependable> dependsOn,
                           Names names,
                           List<String> upstreamUrls,
                           String domain,
                           String clientId,
                           String clientSecret,
                           String cookieSecret,
                           String emailDomain) {
        this.proxyName = names.proxyName();
        AfonPropertiesLoader loader     = new AfonPropertiesLoader();
        Properties           properties = loader.getProperties();

        Map<String, String> proxyLabels = Map.of("app", names.proxyName());

        Secret oauthSecret = Secret.Builder
            .create(scope, names.secretConstructId())
            .metadata(SecretMetadata
                          .builder()
                          .name(names.secretName())
                          .namespace("default")
                          .build())
            .data(Map.of(
                "client-id", clientId,
                "client-secret", clientSecret,
                "cookie-secret", cookieSecret
                        ))
            .build();

        String issuer = OIDC_ISSUER_URL.getValueFrom(properties);

        List<String> args = new ArrayList<>(List.of(
            "--provider=oidc",
            "--oidc-issuer-url=" + issuer));
        args.addAll(emailArgs(properties, emailDomain));
        args.addAll(List.of(
            "--http-address=0.0.0.0:" + PROXY_PORT,
            "--cookie-secure=true",                           // set true when TLS terminates at this proxy
            // Accept id_tokens whose email is unverified: this Keycloak has no SMTP verification flow,
            // so users are provisioned with emailVerified=true, but manually-created users may not be.
            "--insecure-oidc-allow-unverified-email=true",
            // Forward the authenticated identity to upstreams as X-Forwarded-Email. Guacamole's
            // header-auth extension reads this to log the user in with no second login page.
            "--pass-user-headers=true",
            "--skip-provider-button=false",
            "--ping-path=/ping",
            format("--redirect-url=https://%s/oauth2/callback", domain)));
        // One --upstream per app; oauth2-proxy routes by the path component of each URL.
        upstreamUrls.forEach(url -> args.add("--upstream=" + url));

        DeploymentSpecTemplateSpecContainer proxyContainer = DeploymentSpecTemplateSpecContainer
            .builder()
            .name(names.proxyName())
            .image("quay.io/oauth2-proxy/oauth2-proxy:v7.6.0")
            .args(args)
            .env(List.of(
                envFromSecret("OAUTH2_PROXY_CLIENT_ID", names.secretName(), "client-id"),
                envFromSecret("OAUTH2_PROXY_CLIENT_SECRET", names.secretName(), "client-secret"),
                envFromSecret("OAUTH2_PROXY_COOKIE_SECRET", names.secretName(), "cookie-secret")
                        ))
            .build();

        List<ITerraformDependable> deps = new ArrayList<>(dependsOn);
        deps.add(oauthSecret);

        DeploymentUtils.deploy(scope, deps, proxyLabels, List.of(proxyContainer),
                               List.of(waitForIssuer(issuer)), List.of(), names.proxyName(), "15m");

        Service.Builder.create(scope, names.proxyName())
                       .metadata(ServiceMetadata
                                     .builder()
                                     .name(names.proxyName())
                                     .namespace("default")
                                     .build())
                       .dependsOn(deps)
                       .spec(ServiceSpec.builder()
                                        .selector(proxyLabels)
                                        .type("NodePort")
                                        .port(List.of(ServiceSpecPort
                                                          .builder()
                                                          .name("http")
                                                          .port(PROXY_PORT)
                                                          .targetPort(String.valueOf(PROXY_PORT))
                                                          .protocol("TCP")
                                                          .build()))
                                        .build())
                       .build();
    }

    /** The Kubernetes Service name traffic should be routed to. */
    public String proxyServiceName() {
        return proxyName;
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
            .image("curlimages/curl:8.11.1")
            .command(List.of("sh", "-c", script))
            .env(List.of(DeploymentSpecTemplateSpecInitContainerEnv
                             .builder()
                             .name("DISCOVERY_URL")
                             .value(discoveryUrl)
                             .build()))
            .build();
    }

    protected @NonNull List<String> emailArgs(Properties properties, String emailDomain) {
        String restrictTo = emailDomain != null ? emailDomain : OIDC_EMAIL_DOMAIN.getValueFrom(properties);
        return List.of("--email-domain=" + restrictTo);
    }
}
