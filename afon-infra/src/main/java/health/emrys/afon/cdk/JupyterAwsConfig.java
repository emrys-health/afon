package health.emrys.afon.cdk;

import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;
import health.emrys.afon.config.AfonProperties;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.AWS_JUPYTER_DNS_ZONE;
import static health.emrys.afon.config.AfonProperties.AWS_JUPYTER_DOMAIN;
import static health.emrys.afon.infra.Defaults.JUPYTER_INGRESS;

public class JupyterAwsConfig extends JupyterConfig {
    private final AwsConstruct cloudProvider;
    private final String       jupyterDomain;
    private final String       jupyterDnsZoneId;
    private final String jupyterCertArn;

    public JupyterAwsConfig(TerraformStack stack,
                            AwsConstruct cloudProvider,
                            Properties properties) {
        super(stack, properties);
        this.cloudProvider    = cloudProvider;
        this.jupyterDomain    = AWS_JUPYTER_DOMAIN.getValueFrom(properties);
        this.jupyterDnsZoneId = AWS_JUPYTER_DNS_ZONE.getValueFrom(properties);
        this.jupyterCertArn   = AfonProperties.AWS_JUPYTER_CERT_ARN.getValueFrom(properties);
    }

    protected void configureHttpsAndDNS() {
        // for emrysjupyter.click, this is Z03165251T5B540VMLNST
        // run aws route53 list-hosted-zones-by-name --dns-name emrysjupyter.click to see

        // ALB ingress: terminates HTTPS at the load balancer, routes to oauth2-proxy ClusterIP.
        // Traffic flow: internet → ALB (443) → oauth2-proxy:4180 → jupyter-web:8888 → Jupyter pod
        IngressV1 ingress = cloudProvider.createHttpsIngress(
            "jupyter-https-ingress",
            JUPYTER_INGRESS,
            jupyterCertArn,
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
