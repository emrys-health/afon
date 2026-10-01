package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.aws.route53_record.Route53Record;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;
import health.emrys.afon.cdk.CdkHelper;
import health.emrys.afon.cdk.JupyterHubConfig;
import health.emrys.afon.cdk.JupyterMfaConfig;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.cdk.CdkHelper.JUPYTER_INGRESS_NAME;

/**
 * AWS wiring for {@link JupyterHubConfig}: terminates HTTPS at an ALB and routes to the oauth2-proxy
 * MFA gate, which in turn forwards to JupyterHub's public proxy. Mirrors
 * {@link AwsJupyterConfig} but for the multi-user JupyterHub deployment.
 */
public class AwsJupyterHubConfig extends JupyterHubConfig {
    private final AwsCloudProvider cloudProvider;
    private final String         jupyterDomain;
    private final String         jupyterDnsZoneId;
    private final String         jupyterCertArn;
    private final TerraformStack stack;
    private final Route53Record  keycloakAliasRecord;

    /**
     * @param keycloakAliasRecord the DNS record of the stack's own KeyCloak, or {@code null} if the stack uses the
     *                            KeyCloak another stack deploys, whose record then already exists
     */
    public AwsJupyterHubConfig(TerraformStack stack,
                               AwsCloudProvider cloudProvider,
                               AfonPropertyProvider properties,
                               Route53Record keycloakAliasRecord) {
        super(stack, properties);
        this.cloudProvider       = cloudProvider;
        this.jupyterDomain       = properties.getValue(AWS_JUPYTER_DOMAIN_NAME);
        this.jupyterDnsZoneId    = properties.getValue(AWS_JUPYTER_DNS_ZONE);
        this.jupyterCertArn      = properties.getValue(AfonProperty.AWS_JUPYTER_CERT_ARN);
        this.stack               = stack;
        this.keycloakAliasRecord = keycloakAliasRecord;
    }

    @Override
    protected void configureHttpsAndDNS() {
        // ALB ingress: terminates HTTPS at the load balancer, routes to oauth2-proxy ClusterIP.
        // Traffic flow: internet → ALB (443) → oauth2-proxy:4180 → proxy-public:8000 → hub / user pods
        IngressV1 ingress = cloudProvider.createHttpsIngress(
            "jupyterhub-https-ingress",
                JUPYTER_INGRESS_NAME,
            jupyterCertArn,
            jupyterDomain,
            CdkHelper.PROXY_SERVICE_NAME,
            CdkHelper.PROXY_PORT,
            cloudProvider.getSubnetIds());

        cloudProvider.configureDNS(
            "JupyterHubAliasRecord",
            jupyterDnsZoneId,
            jupyterDomain,
            cloudProvider.getLbRelease(),
            ingress,
                JUPYTER_INGRESS_NAME);
    }

    @Override
    protected List<ITerraformDependable> issuerDependencies() {
        // The hub polls the issuer from the moment it starts. The keycloak hostname is the apex of
        // its Route 53 zone, so until its record exists the lookup gets an empty (NODATA) answer, which
        // a resolver upstream of CoreDNS (which itself only caches for 30s) keeps for about 5 minutes
        // and serves to every pod in the cluster. So nothing that polls the issuer may start before
        // the record exists.
        return keycloakAliasRecord == null ? List.of() : List.of(keycloakAliasRecord);
    }

    @Override
    protected JupyterMfaConfig getMFAConfig(List<ITerraformDependable> proxyDeps) {
        // oauth2-proxy performs OIDC discovery against the Keycloak hostname the moment it starts, so
        // it must not be created until that hostname's Route 53 record exists. Without this edge
        // Terraform is free to roll the proxy out alongside — or before — the record, and the pod
        // resolves NXDOMAIN. The wait-for-issuer init container still covers DNS propagation lag,
        // which no dependency edge can express.
        List<ITerraformDependable> deps = new ArrayList<>(proxyDeps);
        if (keycloakAliasRecord != null) {
            deps.add(keycloakAliasRecord);
        }
        return new JupyterMfaConfig(
            this.stack,
            deps,
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