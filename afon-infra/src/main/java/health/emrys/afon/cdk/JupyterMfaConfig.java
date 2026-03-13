package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.TerraformVariable;
import com.hashicorp.cdktf.TerraformVariableConfig;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainer;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerEnv;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerEnvValueFrom;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerEnvValueFromSecretKeyRef;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.infra.Defaults.JUPYTER_DOMAIN;
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
 *
 * <p><b>Before running {@code tofu apply}, export these variables:</b>
 * <pre>
 *   export TF_VAR_oauth2_client_id=&lt;your-client-id&gt;
 *   export TF_VAR_oauth2_client_secret=***REMOVED***
 *   export TF_VAR_oauth2_cookie_secret=$(openssl rand -base64 32 | tr -- '+/' '-_')
 * </pre>
 *
 * <p><b>OAuth2 app registration (Google example):</b>
 * <ol>
 *   <li>Go to Google Cloud Console → APIs &amp; Services → Credentials</li>
 *   <li>Create an OAuth 2.0 Client ID of type "Web application"</li>
 *   <li>Add authorized redirect URI: {@code http://&lt;load-balancer-hostname&gt;/oauth2/callback}</li>
 *   <li>Copy the Client ID and Client Secret into the TF_VAR_ exports above</li>
 * </ol>
 *
 * <p>To restrict access to a specific domain (e.g. your organisation), change {@code --email-domain=*}
 * to {@code --email-domain=yourdomain.com}. To use a different OIDC provider, change
 * {@code --oidc-issuer-url} and {@code --provider} accordingly.
 */
class JupyterMfaConfig {

    static final int                 PROXY_PORT     = 4180;
    static final Map<String, String> PROXY_LABELS   = Map.of("app", "oauth2-proxy");
    static final String              PROXY_SVC_NAME = "oauth2-proxy";

    private static final String SECRET_NAME = "oauth2-proxy-secret";

    JupyterMfaConfig(Construct scope, List<ITerraformDependable> dependsOn, String jupyterUpstreamUrl) {
        TerraformVariable clientId = new TerraformVariable(scope, "oauth2_client_id",
                TerraformVariableConfig.builder()
                                       .type("string")
                                       .sensitive(true)
                                       .description("OAuth2 client ID from your OIDC provider (e.g. Google Cloud Console)")
                                       .build());

        TerraformVariable clientSecret = new TerraformVariable(scope, "oauth2_client_secret",
                TerraformVariableConfig.builder()
                                       .type("string")
                                       .sensitive(true)
                                       .description("OAuth2 client secret from your OIDC provider")
                                       .build());

        TerraformVariable cookieSecret = new TerraformVariable(scope, "oauth2_cookie_secret",
                TerraformVariableConfig.builder()
                                       .type("string")
                                       .sensitive(true)
                                       .description("32-byte base64 string for cookie encryption — " +
                                                     "generate with: openssl rand -base64 32 | tr -- '+/' '-_'")
                                       .build());

        Secret oauthSecret = Secret.Builder.create(scope, "oauth2ProxySecret")
                                           .metadata(SecretMetadata.builder()
                                                                    .name(SECRET_NAME)
                                                                    .namespace("default")
                                                                    .build())
                                           .data(Map.of(
                                                   "client-id",     clientId.getStringValue(),
                                                   "client-secret", clientSecret.getStringValue(),
                                                   "cookie-secret", cookieSecret.getStringValue()
                                                       ))
                                           .build();

        DeploymentSpecTemplateSpecContainer proxyContainer = DeploymentSpecTemplateSpecContainer.builder()
                .name("oauth2-proxy")
                .image("quay.io/oauth2-proxy/oauth2-proxy:v7.6.0")
                .args(List.of(
                        "--provider=oidc",
                        "--oidc-issuer-url=https://accounts.google.com",  // swap for Azure AD, GitHub, etc.
                        "--email-domain=*",                                // restrict to e.g. --email-domain=yourdomain.com
                        "--upstream=" + jupyterUpstreamUrl,
                        "--http-address=0.0.0.0:" + PROXY_PORT,
                        "--cookie-secure=true",                           // set true when TLS terminates at this proxy
                        "--skip-provider-button=false",
                        "--ping-path=/ping",
                        format("--redirect-url=https://%s/oauth2/callback", JUPYTER_DOMAIN)
                     ))
                .env(List.of(
                        envFromSecret("OAUTH2_PROXY_CLIENT_ID",     SECRET_NAME, "client-id"),
                        envFromSecret("OAUTH2_PROXY_CLIENT_SECRET", SECRET_NAME, "client-secret"),
                        envFromSecret("OAUTH2_PROXY_COOKIE_SECRET", SECRET_NAME, "cookie-secret")
                    ))
                .build();

        List<ITerraformDependable> deps = new ArrayList<>(dependsOn);
        deps.add(oauthSecret);

        DeploymentUtils.deploy(scope, deps, PROXY_LABELS, List.of(proxyContainer), List.of(), "oauth2-proxy");

        Service.Builder.create(scope, PROXY_SVC_NAME)
                       .metadata(ServiceMetadata.builder()
                                                .name(PROXY_SVC_NAME)
                                                .namespace("default")
                                                .build())
                       .dependsOn(deps)
                       .spec(ServiceSpec.builder()
                                        .selector(PROXY_LABELS)
                                        .type("NodePort")
                                        .port(List.of(ServiceSpecPort.builder()
                                                                      .name("http")
                                                                      .port(PROXY_PORT)
                                                                      .targetPort(String.valueOf(PROXY_PORT))
                                                                      .protocol("TCP")
                                                                      .build()))
                                        .build())
                       .build();
    }

    private static DeploymentSpecTemplateSpecContainerEnv envFromSecret(
            String envName, String secretName, String secretKey) {
        return DeploymentSpecTemplateSpecContainerEnv.builder()
                .name(envName)
                .valueFrom(DeploymentSpecTemplateSpecContainerEnvValueFrom.builder()
                                   .secretKeyRef(DeploymentSpecTemplateSpecContainerEnvValueFromSecretKeyRef.builder()
                                                         .name(secretName)
                                                         .key(secretKey)
                                                         .build())
                                   .build())
                .build();
    }
}
