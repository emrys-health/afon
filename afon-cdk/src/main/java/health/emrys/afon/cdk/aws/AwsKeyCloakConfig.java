package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.providers.aws.route53_record.Route53Record;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;
import health.emrys.afon.cdk.CdkHelper;
import health.emrys.afon.cdk.KeyCloakConfig;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import static health.emrys.afon.security.keycloak.KeyCloakConfigurer.KEYCLOAK_PORT;

public class AwsKeyCloakConfig extends KeyCloakConfig<AwsCloudProvider> {
    private final String dnsZone;
    private final String certArn;
    private final String hostname;

    private Route53Record aliasRecord;

    public AwsKeyCloakConfig(
        @NonNull Construct scope,
        @NonNull AfonPropertyProvider properties,
        @NonNull AwsCloudProvider cloudProvider) {
        super(scope, properties, cloudProvider);
        dnsZone  = properties.getValue(AfonProperty.AWS_KEYCLOAK_DNS_ZONE);
        certArn  = properties.getValue(AfonProperty.AWS_KEYCLOAK_CERT_ARN);
        hostname = properties.getValue(AfonProperty.OIDC_DOMAIN_NAME);
    }

    @Override
    protected void configureHttpsAndDNS() {
        IngressV1 ingress = cloudProvider.createHttpsIngress(
            CdkHelper.KEYCLOAK_HTTPS_INGRESS_NAME,
            CdkHelper.KEYCLOAK_INGRESS_NAME,
            certArn,
            hostname,
            CdkHelper.KEYCLOAK_SERVICE_NAME,
            KEYCLOAK_PORT,
            cloudProvider.getSubnetIds());

        aliasRecord = cloudProvider.configureDNS(
            "KeyCloakAliasRecord",
            dnsZone, // aws route53 list-hosted-zones-by-name --dns-name emrysjupyter.click
            hostname,
            cloudProvider.getLbRelease(),
            ingress,
            CdkHelper.KEYCLOAK_INGRESS_NAME);
    }

    /**
     * The Route 53 record that makes the Keycloak hostname — and hence the OIDC issuer — resolvable.
     * Null until {@link #configureHttpsAndDNS()} has run. Anything that performs OIDC discovery at
     * startup should depend on this, or it may be created while the hostname still does not exist.
     */
    public Route53Record getAliasRecord() {
        return aliasRecord;
    }
}
