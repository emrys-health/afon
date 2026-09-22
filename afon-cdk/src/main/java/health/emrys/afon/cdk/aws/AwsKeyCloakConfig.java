package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.providers.aws.route53_record.Route53Record;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;
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
        hostname = properties.getValue(AfonProperty.KC_HOSTNAME);
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
            cloudProvider.getSubnetIds(),
            // Keycloak answers 302 on "/" (it redirects to the admin console), so the ALB
            // default health check could never pass. A realm endpoint returns 200 and
            // exercises the database, which is what actually fails.
            "/realms/master",
            null);

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
