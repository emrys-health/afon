package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;
import com.hashicorp.cdktf.providers.kubernetes.deployment.Deployment;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import health.emrys.afon.cdk.PolarisConfig;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.StackId;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static health.emrys.afon.config.AfonProperty.AWS_POLARIS_ALLOWED_CIDRS;
import static health.emrys.afon.polaris.PolarisConstants.POLARIS_INTERNAL_SERVICE_NAME;

/**
 * Deploys Polaris, behind its HTTPS ingress and DNS record at {@code AWS_POLARIS_DOMAIN_NAME}.  Polaris uses the
 * KeyCloak server at {@code OIDC_DOMAIN_NAME}, which the {@link AwsKeycloakStack} deploys, so that both can run side
 * by side, while the stacks extending this one, eg {@link AwsFullStack}, deploy their own KeyCloak, unless
 * {@code AWS_KEYCLOAK_EXTERNAL} is set, see {@link #isKeyCloakExternal}.
 */
public class AwsPolarisStack extends AbstractAwsStack {

    private static final Logger logger = LoggerFactory.getLogger(AwsPolarisStack.class);

    protected final PolarisConfig polarisConfig;

    private final String polarisDomainName;

    private final AwsKeyCloakConfig keyCloakConfig;

    public AwsPolarisStack(@NonNull Construct scope,
                           StackId stackId,
                           AfonPropertyProvider properties) {
        this(scope, stackId, properties, false);
    }

    /**
     * @param withKeyCloak whether the stack deploys its own KeyCloak, rather than using the one another stack deploys
     */
    protected AwsPolarisStack(@NonNull Construct scope,
                              StackId stackId,
                              AfonPropertyProvider properties,
                              boolean withKeyCloak) {
        super(scope, stackId, properties, true);
        polarisDomainName = properties.getValue(AfonProperty.AWS_POLARIS_DOMAIN_NAME);
        configurePolarisHttpsAndDNS(properties);
        List<ITerraformDependable> polarisDependables = new ArrayList<>(cloudProvider.getDependables());
        if (withKeyCloak) {
            // Keycloak's wiring is created first so that Polaris, which does OIDC discovery against the
            // Keycloak hostname, can be ordered after that hostname's DNS record.
            keyCloakConfig = new AwsKeyCloakConfig(this, properties, cloudProvider);
            keyCloakConfig.configureHttpsAndDNS();
            polarisDependables.add(keyCloakConfig.getAliasRecord());
        } else {
            keyCloakConfig = null;
            logger.info("Stack {} deploys no KeyCloak, but uses the one at {} ({}), which the aws-keycloak stack deploys{}",
                        stackId, properties.getValue(AfonProperty.OIDC_DOMAIN_NAME), AfonProperty.OIDC_DOMAIN_NAME,
                        isKeyCloakExternal(properties) ? ", as configured by " + AfonProperty.AWS_KEYCLOAK_EXTERNAL + "=true" : "");
        }
        polarisConfig = new PolarisConfig(this, "polaris", cloudProvider, properties) {
            protected Service configurePolarisService(Deployment polarisDeployment) {
                return null; // no longer using an external service
            }
        };
        polarisConfig.deployPolaris(polarisDependables);
        Service.Builder
            .create(this, POLARIS_INTERNAL_SERVICE_NAME)
            .metadata(ServiceMetadata
                          .builder()
                          .name(POLARIS_INTERNAL_SERVICE_NAME)
                          .namespace("default")
                          .build())
            .dependsOn(cloudProvider.getDependables())
            .spec(ServiceSpec
                      .builder()
                      .selector(polarisConfig.getPolarisLabels()) // Selects the Pods created by secretAppDeployment
                      .type("NodePort")
                      .internalTrafficPolicy("Cluster")
                      .clusterIps(List.of())
                      .port(List.of(polarisConfig.getPolarisSpecPort()))
                      .build())
            .build();
    }

    /**
     * Exposes Polaris via its HTTPS ingress, and makes it resolvable via its Route 53 record.
     */
    private void configurePolarisHttpsAndDNS(AfonPropertyProvider properties) {
        IngressV1 ingress = cloudProvider.createHttpsIngress(
            "polaris-https-ingress",
            "polaris-ingress",
            properties.getValue(AfonProperty.AWS_POLARIS_CERT_ARN),
            polarisDomainName,
            POLARIS_INTERNAL_SERVICE_NAME,
            8181,
            cloudProvider.getSubnetIds(),
            cloudProvider.polarisInboundCidrs(properties.getValue(AWS_POLARIS_ALLOWED_CIDRS, "")));
        cloudProvider.configureDNS(
            "IngressAliasRecord",
            properties.getValue(AfonProperty.AWS_POLARIS_DNS_ZONE),
            polarisDomainName,
            cloudProvider.getLbRelease(),
            ingress,
            "polaris-ingress");
    }

    /**
     * Keycloak's AWS wiring, exposed so subclasses deploying their own KeyCloak can depend on its DNS record (see
     * {@link AwsKeyCloakConfig#getAliasRecord()}).
     *
     * @return the KeyCloak wiring, or {@code null} if the stack uses the KeyCloak another stack deploys
     */
    protected AwsKeyCloakConfig getKeyCloakConfig() {
        return keyCloakConfig;
    }

    @Override
    public String getPolarisDomainName() {
        return polarisDomainName;
    }

    public static void main(String[] args) {
        final App app = createApp();
        new AwsPolarisStack(app, StackId.of(AwsPolarisStack.class), new FilePropertyProvider());
        app.synth();
    }

}
