package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;
import com.hashicorp.cdktf.providers.kubernetes.deployment.Deployment;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import health.emrys.afon.cdk.PolarisConfig;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.polaris.PolarisConstants.POLARIS_INTERNAL_SERVICE_NAME;
import static health.emrys.afon.config.AfonCloud.AWS;

public class AwsPolarisStack extends AbstractAwsStack {

    protected final PolarisConfig polarisConfig;

    private final String polarisDomainName;

    private AwsKeyCloakConfig keyCloakConfig;

    public AwsPolarisStack(@NonNull Construct scope,
                           AfonCloud id,
                           AfonPropertyProvider properties) {
        super(scope, id, properties);
        polarisDomainName = properties.getValue(AfonProperty.AWS_POLARIS_DOMAIN_NAME);
        configurePolarisHttpsAndDNS(properties);
        // Keycloak's wiring is created first so that Polaris, which does OIDC discovery against the
        // Keycloak hostname, can be ordered after that hostname's DNS record.
        keyCloakConfig = new AwsKeyCloakConfig(this, properties, cloudProvider);
        keyCloakConfig.configureHttpsAndDNS();
        polarisConfig = new PolarisConfig(this, "polaris", cloudProvider, properties) {
            protected Service configurePolarisService(Deployment polarisDeployment) {
                return null; // no longer using an external service
            }
        };
        List<ITerraformDependable> polarisDependables = new ArrayList<>(cloudProvider.getDependables());
        polarisDependables.add(keyCloakConfig.getAliasRecord());
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
            cloudProvider.getSubnetIds());
        cloudProvider.configureDNS(
            "IngressAliasRecord",
            properties.getValue(AfonProperty.AWS_POLARIS_DNS_ZONE),
            polarisDomainName,
            cloudProvider.getLbRelease(),
            ingress,
            "polaris-ingress");
    }

    /**
     * Keycloak's AWS wiring, exposed so subclasses can depend on its DNS record (see
     * {@link AwsKeyCloakConfig#getAliasRecord()}).
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
        new AwsPolarisStack(app, AWS, new FilePropertyProvider());
        app.synth();
    }

}
