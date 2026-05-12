package health.emrys.afon.cdk;

import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.AWS_JUPYTER_DNS_ZONE;
import static health.emrys.afon.config.AfonProperties.AWS_JUPYTER_DOMAIN;
import static java.lang.String.format;

public class JupyterAwsConfig extends JupyterConfig {
    private final AwsConstruct cloudProvider;
    private final String       jupyterDomain;
    private final String       jupyterDnsZoneId;

    public JupyterAwsConfig(TerraformStack stack,
                            AwsConstruct cloudProvider,
                            Properties properties) {
        super(stack, properties);
        this.cloudProvider    = cloudProvider;
        this.jupyterDomain    = AWS_JUPYTER_DOMAIN.getValueFrom(properties);
        this.jupyterDnsZoneId = AWS_JUPYTER_DNS_ZONE.getValueFrom(properties);
    }

    protected void configureHttpsAndDNS() {
        // for emrysjupyter.click, this is Z03165251T5B540VMLNST
        // run aws route53 list-hosted-zones-by-name --dns-name emrysjupyter.click to see

        // ALB ingress: terminates HTTPS at the load balancer, routes to oauth2-proxy ClusterIP.
        // Traffic flow: internet → ALB (443) → oauth2-proxy:4180 → jupyter-web:8888 → Jupyter pod
        IngressV1 ingress = cloudProvider.createHttpsIngress(
            "jupyter-https-ingress",
            "jupyter-ingress",
            format(
                "arn:aws:acm:%s:%s:certificate/8f58f850-3756-40d4-b7b5-499124f150e3",
                cloudProvider.getRegion(),
                cloudProvider.getAccountId()),
            jupyterDomain,
            JupyterMfaConfig.PROXY_SVC_NAME,
            JupyterMfaConfig.PROXY_PORT,
            cloudProvider.getSubnetIds());

        cloudProvider.configureDNS(
            "JupyterAliasRecord",
            jupyterDnsZoneId, // aws route53 list-hosted-zones-by-name --dns-name emrysjupyter.click
            jupyterDomain, cloudProvider.getLbRelease(), ingress);
    }

    @Override
    protected String getJupyterDomain() {
        return jupyterDomain;
    }
}
