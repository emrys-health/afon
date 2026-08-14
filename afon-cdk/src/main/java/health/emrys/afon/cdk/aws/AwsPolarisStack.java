package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.kubernetes.deployment.Deployment;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import health.emrys.afon.cdk.AfonCloudStack;
import health.emrys.afon.cdk.PolarisConfig;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.FilePropertyProvider;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.cdk.PolarisConfig.POLARIS_INTERNAL;
import static health.emrys.afon.config.AfonCloud.AWS;

public class AwsPolarisStack extends TerraformStack implements AfonCloudStack {

    protected final PolarisConfig polarisConfig;
    protected final AwsCloudProvider cloudProvider;

    private AwsKeyCloakConfig keyCloakConfig;

    public AwsPolarisStack(@NonNull Construct scope,
                           AfonCloud id,
                           Properties properties) {
        super(scope, id.toString());
        NullProvider.Builder.create(this, "nullProvider")
                            .build();
        cloudProvider = new AwsCloudProvider(this, id.toString(), properties);
        polarisConfig = new PolarisConfig(this, "polaris", cloudProvider) {
            protected Service configurePolarisService(Deployment polarisDeployment, Map<String, String> polarisLabels) {
                return null; // no longer using an external service
            }
        };
        polarisConfig.deployPolaris(cloudProvider.getDependables());
        Service.Builder
            .create(this, POLARIS_INTERNAL)
            .metadata(ServiceMetadata
                          .builder()
                          .name(POLARIS_INTERNAL)
                          .namespace("default")
                          .build())
            .dependsOn(cloudProvider.getDependables())
            .spec(ServiceSpec
                      .builder()
                      .selector(polarisConfig.polarisLabels) // Selects the Pods created by secretAppDeployment
                      .type("NodePort")
                      .internalTrafficPolicy("Cluster")
                      .clusterIps(List.of())
                      .port(List.of(polarisConfig.POLARIS_PORTS))
                      .build())
            .build();
        keyCloakConfig = new AwsKeyCloakConfig(this, properties, cloudProvider);
        keyCloakConfig.configureHttpsAndDNS();
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
        final App app = new App();
        new AwsPolarisStack(app, AWS, new FilePropertyProvider().getProperties());
        app.synth();
    }

}
