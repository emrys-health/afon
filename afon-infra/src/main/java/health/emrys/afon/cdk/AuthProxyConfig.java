package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainer;
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

        List<String> argsForOAuthProxy = List.of(
            "--provider=oidc",
            "--oidc-issuer-url=" + OIDC_ISSUER_URL.getValueFrom(properties),
            "--upstream=" + upstreamUrl,
            "--http-address=0.0.0.0:" + PROXY_PORT,
            "--cookie-secure=true",                           // set true when TLS terminates at this proxy
            "--skip-provider-button=false",
            "--ping-path=/ping",
            format("--redirect-url=https://%s/oauth2/callback", domain)
                                                );
        List<String> args = new ArrayList<>();
        args.addAll(emailArgs(properties, emailDomain));
        args.addAll(argsForOAuthProxy);
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

        DeploymentUtils.deploy(scope, deps, proxyLabels, List.of(proxyContainer), List.of(), names.proxyName());

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

    protected @NonNull List<String> emailArgs(Properties properties, String emailDomain) {
        String restrictTo = emailDomain != null ? emailDomain : OIDC_EMAIL_DOMAIN.getValueFrom(properties);
        return List.of("--email-domain=" + restrictTo);
    }
}
