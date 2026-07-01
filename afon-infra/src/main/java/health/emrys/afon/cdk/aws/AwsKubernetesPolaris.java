package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.kubernetes.deployment.Deployment;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import health.emrys.afon.cdk.EmrysCloud;
import health.emrys.afon.cdk.KeyCloakDeploymentConfig;
import health.emrys.afon.cdk.PolarisConfig;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.config.AfonPropertiesLoader;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.cdk.PolarisConfig.POLARIS_INTERNAL;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AWS;

public class AwsKubernetesPolaris extends TerraformStack implements EmrysCloud {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(AwsKubernetesPolaris.class);

    public static final SupportedCloudsEnum ID               = AWS;

    protected final PolarisConfig polarisConfig;
    protected final AwsConstruct  cloudProvider;

    public AwsKubernetesPolaris(@NotNull Construct scope,
                                SupportedCloudsEnum id,
                                Properties properties) {
        super(scope, id.toString());
        NullProvider.Builder.create(this, "nullProvider")
                            .build();
        cloudProvider = new AwsConstruct(this, id.toString(), properties);
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
        new AwsKeyCloakDeploymentConfig(this, properties, cloudProvider);
    }

    public static void main(String[] args) {
        final App app = new App();
        new AwsKubernetesPolaris(app, ID, new AfonPropertiesLoader().getProperties());
        app.synth();
        logger.warn("Don't forget to export KUBE_CONFIG_PATH=$HOME/.kube/config and unalias aws before running tofu " +
                        "-apply");
    }

    @Override
    public SupportedCloudsEnum getId() {
        return ID;
    }

    @Override
    public String getPolarisDomainName() {
        return cloudProvider.getPolarisDomainName();
    }
}
