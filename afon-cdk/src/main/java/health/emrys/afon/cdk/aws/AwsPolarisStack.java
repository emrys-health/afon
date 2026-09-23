package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.deployment.Deployment;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import health.emrys.afon.cdk.AbstractCloudStack;
import health.emrys.afon.cdk.PolarisConfig;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.polaris.PolarisConstants.POLARIS_INTERNAL_SERVICE_NAME;
import static health.emrys.afon.config.AfonCloud.AWS;

public class AwsPolarisStack extends AbstractCloudStack {

    protected final PolarisConfig polarisConfig;
    protected final AwsCloudProvider cloudProvider;

    private AwsKeyCloakConfig keyCloakConfig;

    public AwsPolarisStack(@NonNull Construct scope,
                           AfonCloud id,
                           AfonPropertyProvider properties) {
        super(scope, id);
        NullProvider.Builder.create(this, "nullProvider")
                            .build();
        cloudProvider = new AwsCloudProvider(this, id.toString(), properties);
        cloudProvider.configureHelm();
        this.addOverride(
            "terraform.required_providers.aws", Map.of(
                "source", "hashicorp/aws",
                "version", "6.27.0"   // 👈 Pin exact version
                                                      ));
        this.addOverride(
            "terraform.required_providers.helm", Map.of(
                "source", "hashicorp/helm",
                "version", "3.2.0"   // 👈 Pin exact version
                                                       ));
        this.addOverride(
            "terraform.required_providers.kubernetes", Map.of(
                "source", "hashicorp/kubernetes",
                // 3.2.1 fixes "Unexpected Identity Change" errors when a resource takes
                // extended time to become ready (as happened when an EKS token expired
                // mid-rollout of oauth2-proxy). Do not downgrade below 3.2.1.
                "version", "3.2.1"   // 👈 Pin exact version
                                                             ));
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
     * Keycloak's AWS wiring, exposed so subclasses can depend on its DNS record (see
     * {@link AwsKeyCloakConfig#getAliasRecord()}).
     */
    protected AwsKeyCloakConfig getKeyCloakConfig() {
        return keyCloakConfig;
    }

    @Override
    public AfonCloud getId() {
        return AWS;
    }

    @Override
    public String getPolarisDomainName() {
        return cloudProvider.getPolarisDomainName();
    }

    public static void main(String[] args) {
        final App app = createApp();
        new AwsPolarisStack(app, AWS, new FilePropertyProvider());
        app.synth();
    }

}
