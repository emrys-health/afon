package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.providers.aws.route53_record.Route53Record;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;
import health.emrys.afon.cdk.KeyCloakDeploymentConfig;
import health.emrys.afon.config.AfonProperties;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.Properties;

import static health.emrys.afon.security.keycloak.KeyCloakConfig.KEYCLOAK_PORT;

public class AwsKeyCloakDeploymentConfig extends KeyCloakDeploymentConfig<AwsCloudProvider> {
    private final String dnsZone;
    private final String certArn;
    private final String hostname;

    private Route53Record aliasRecord;

    public AwsKeyCloakDeploymentConfig(
        @NonNull Construct scope,
        @NonNull Properties properties,
        @NonNull AwsCloudProvider cloudProvider) {
        super(scope, properties, cloudProvider);
        dnsZone  = AfonProperties.AWS_KEYCLOAK_DNS_ZONE.getValueFrom(properties);
        certArn  = AfonProperties.AWS_KEYCLOAK_CERT_ARN.getValueFrom(properties);
        hostname = AfonProperties.KC_HOSTNAME.getValueFrom(properties);
    }

    @Override
    protected void configureHttpsAndDNS() {
        IngressV1 ingress = cloudProvider.createHttpsIngress(
            KEYCLOAK_HTTPS_INGRESS,
            KEYCLOAK_INGRESS,
            certArn,
            hostname,
            KC_SVC,
            KEYCLOAK_PORT,
            cloudProvider.getSubnetIds());

        aliasRecord = cloudProvider.configureDNS(
            "KeyCloakAliasRecord",
            dnsZone, // aws route53 list-hosted-zones-by-name --dns-name emrysjupyter.click
            hostname,
            cloudProvider.getLbRelease(),
            ingress,
            KEYCLOAK_INGRESS);
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
