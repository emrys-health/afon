package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;
import health.emrys.afon.cdk.JupyterHubConfig;
import health.emrys.afon.cdk.JupyterMfaConfig;
import health.emrys.afon.config.AfonProperties;

import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.infra.Defaults.JUPYTER_INGRESS;

/**
 * AWS wiring for {@link JupyterHubConfig}: terminates HTTPS at an ALB and routes to the oauth2-proxy
 * MFA gate, which in turn forwards to JupyterHub's public proxy. Mirrors
 * {@link JupyterAwsConfig} but for the multi-user JupyterHub deployment.
 */
public class JupyterHubAwsConfig extends JupyterHubConfig {
    private final AwsConstruct   cloudProvider;
    private final String         jupyterDomain;
    private final String         jupyterDnsZoneId;
    private final String         jupyterCertArn;
    private final TerraformStack stack;

    public JupyterHubAwsConfig(TerraformStack stack,
                               AwsConstruct cloudProvider,
                               Properties properties) {
        super(stack, properties);
        this.cloudProvider    = cloudProvider;
        this.jupyterDomain    = AWS_JUPYTER_DOMAIN.getValueFrom(properties);
        this.jupyterDnsZoneId = AWS_JUPYTER_DNS_ZONE.getValueFrom(properties);
        this.jupyterCertArn   = AfonProperties.AWS_JUPYTER_CERT_ARN.getValueFrom(properties);
        this.stack            = stack;
    }

    @Override
    protected void configureHttpsAndDNS() {
        // ALB ingress: terminates HTTPS at the load balancer, routes to oauth2-proxy ClusterIP.
        // Traffic flow: internet → ALB (443) → oauth2-proxy:4180 → proxy-public:8000 → hub / user pods
        IngressV1 ingress = cloudProvider.createHttpsIngress(
            "jupyterhub-https-ingress",
            JUPYTER_INGRESS,
            jupyterCertArn,
            jupyterDomain,
            JupyterMfaConfig.PROXY_SVC_NAME,
            JupyterMfaConfig.PROXY_PORT,
            cloudProvider.getSubnetIds());

        cloudProvider.configureDNS(
            "JupyterHubAliasRecord",
            jupyterDnsZoneId,
            jupyterDomain, cloudProvider.getLbRelease(), ingress);
    }

    @Override
    protected JupyterMfaConfig getMFAConfig(List<ITerraformDependable> proxyDeps) {
        return new JupyterMfaConfig(
            this.stack,
            proxyDeps,
            JupyterHubConfig.MFA_UPSTREAMS,
            jupyterDomain,
            OAUTH2_CLIENT_ID.getValueFrom(properties),
            OAUTH2_CLIENT_SECRET.getValueFrom(properties),
            OAUTH2_COOKIE_SECRET.getValueFrom(properties));
    }

    @Override
    protected String jupyterDomain() {
        return jupyterDomain;
    }

    @Override
    protected String oauthClientId() {
        return OAUTH2_CLIENT_ID.getValueFrom(properties);
    }

    @Override
    protected String oauthClientSecret() {
        return OAUTH2_CLIENT_SECRET.getValueFrom(properties);
    }
}