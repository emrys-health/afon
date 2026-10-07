package health.emrys.afon.cdk.azure;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;
import health.emrys.afon.cdk.CdkHelper;
import health.emrys.afon.cdk.JupyterHubConfig;
import health.emrys.afon.cdk.JupyterMfaConfig;
import health.emrys.afon.config.AfonPropertyProvider;

import java.util.List;
import java.util.Optional;

import static health.emrys.afon.cdk.CdkHelper.JUPYTER_INGRESS_NAME;
import static health.emrys.afon.cdk.CdkHelper.PROXY_PORT;
import static health.emrys.afon.cdk.CdkHelper.PROXY_SERVICE_NAME;
import static health.emrys.afon.config.AfonProperty.*;

/**
 * Azure wiring for {@link JupyterHubConfig}: terminates HTTPS at the nginx ingress (cert-manager TLS)
 * and routes to the oauth2-proxy MFA gate, which in turn forwards to JupyterHub's public proxy.
 * Mirrors {@code AwsJupyterHubConfig}, including the OAuth client properties it reads.
 */
public class AzureJupyterHubConfig extends JupyterHubConfig {
    private static final String TLS_SECRET_NAME = "jupyter-secret";

    private final AzurePolarisStack stack;
    private final String            jupyterDomain;
    private final CdkHelper         helper;

    public AzureJupyterHubConfig(AzurePolarisStack stack, AfonPropertyProvider properties) {
        super(stack, properties);
        this.stack         = stack;
        this.jupyterDomain = properties.getValue(AZURE_JUPYTER_DOMAIN_NAME);
        this.helper        = new CdkHelper(
            stack, jupyterDomain, TLS_SECRET_NAME,
            PROXY_PORT, PROXY_SERVICE_NAME, JUPYTER_INGRESS_NAME);
    }

    @Override
    protected void configureHttpsAndDNS() {
        IngressV1 ingress = helper.createIngress(
            List.of(stack.nginxRelease, stack.certManager),
            Optional.empty());
        stack.configureDns(ingress, jupyterDomain);
    }

    @Override
    protected JupyterMfaConfig getMFAConfig(List<ITerraformDependable> proxyDeps) {
        return new JupyterMfaConfig(
            stack,
            proxyDeps,
            properties,
            JupyterHubConfig.MFA_UPSTREAMS,
            jupyterDomain,
            properties.getValue(OAUTH2_CLIENT_ID),
            properties.getValue(OAUTH2_CLIENT_SECRET),
            properties.getValue(OAUTH2_COOKIE_SECRET));
    }

    @Override
    protected String jupyterDomain() {
        return jupyterDomain;
    }

    @Override
    protected String oauthClientId() {
        return properties.getValue(OAUTH2_CLIENT_ID);
    }

    @Override
    protected String oauthClientSecret() {
        return properties.getValue(OAUTH2_CLIENT_SECRET);
    }
}
