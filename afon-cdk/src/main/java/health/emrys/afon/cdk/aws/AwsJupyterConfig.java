package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;
import health.emrys.afon.cdk.JupyterConfig;
import health.emrys.afon.cdk.JupyterMfaConfig;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;

import java.util.List;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.cluster.Defaults.JUPYTER_INGRESS;

public class AwsJupyterConfig extends JupyterConfig {
    private final AwsCloudProvider cloudProvider;
    private final String         jupyterDomain;
    private final String         jupyterDnsZoneId;
    private final String         jupyterCertArn;
    private final TerraformStack stack;

    public AwsJupyterConfig(TerraformStack stack,
                            AwsCloudProvider cloudProvider,
                            AfonPropertyProvider properties) {
        super(stack, properties);
        this.cloudProvider    = cloudProvider;
        this.jupyterDomain    = properties.getValue(AWS_JUPYTER_DOMAIN);
        this.jupyterDnsZoneId = properties.getValue(AWS_JUPYTER_DNS_ZONE);
        this.jupyterCertArn   = properties.getValue(AfonProperty.AWS_JUPYTER_CERT_ARN);
        this.stack            = stack;
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
            jupyterDomain, cloudProvider.getLbRelease(), ingress, JUPYTER_INGRESS);
    }

    @Override
    protected JupyterMfaConfig getMFAConfig(List<ITerraformDependable> proxyDeps) {
        return new JupyterMfaConfig(
            this.stack,
            proxyDeps,
            properties,
            "http://guacamole-web:8080",
            jupyterDomain,
            properties.getValue(OAUTH2_CLIENT_ID),
            properties.getValue(OAUTH2_CLIENT_SECRET),
            properties.getValue(OAUTH2_COOKIE_SECRET));
    }


}
