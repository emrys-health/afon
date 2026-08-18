package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;
import health.emrys.afon.cdk.PomeriumConfig;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static health.emrys.afon.cdk.PomeriumConfig.*;
import static health.emrys.afon.config.AfonProperties.AWS_POMERIUM_CERT_ARN;
import static health.emrys.afon.config.AfonProperties.AWS_POMERIUM_DNS_ZONE;

/**
 * AWS wiring for {@link PomeriumConfig}: one ALB terminating HTTPS for BOTH the authenticate host
 * and the protected verify route host (Pomerium routes by Host header), each with its own Route 53
 * alias record. The ACM certificate must cover both hostnames (wildcard or multi-SAN).
 *
 * <p>Traffic flow: internet → ALB (443) → pomerium:8080 → pomerium-verify:8000
 *
 * <p>Like oauth2-proxy, Pomerium performs OIDC discovery against the KeyCloak hostname on startup,
 * so callers must include KeyCloak's Route 53 alias record (see
 * {@link AwsKeyCloakConfig#getAliasRecord()}) in {@code dependsOn}.
 */
public class AwsPomeriumConfig {

    public AwsPomeriumConfig(
        @NonNull Construct scope,
        @NonNull Properties properties,
        @NonNull AwsCloudProvider cloudProvider,
        @NonNull List<ITerraformDependable> dependsOn) {

        List<ITerraformDependable> deps = new ArrayList<>(dependsOn);
        // The Pomerium Service is NodePort, so its creation triggers the load balancer controller's
        // mutating webhook; wait for the controller to be serving (see KeyCloakConfig).
        deps.addAll(cloudProvider.getServiceDependables());
        PomeriumConfig pomerium = new PomeriumConfig(scope, deps, properties);

        String certArn = AWS_POMERIUM_CERT_ARN.getValueFrom(properties);
        String dnsZone = AWS_POMERIUM_DNS_ZONE.getValueFrom(properties);

        IngressV1 ingress = cloudProvider.createHttpsIngress(
            "pomerium-https-ingress",
            POMERIUM_INGRESS,
            certArn,
            List.of(pomerium.getAuthenticateHost(), pomerium.getVerifyHost()),
            POMERIUM_SVC,
            POMERIUM_PORT,
            cloudProvider.getSubnetIds());

        cloudProvider.configureDNS(
            "PomeriumAuthenticateAliasRecord",
            dnsZone,
            pomerium.getAuthenticateHost(),
            cloudProvider.getLbRelease(),
            ingress,
            POMERIUM_INGRESS);

        cloudProvider.configureDNS(
            "PomeriumVerifyAliasRecord",
            dnsZone,
            pomerium.getVerifyHost(),
            cloudProvider.getLbRelease(),
            ingress,
            POMERIUM_INGRESS);
    }
}
