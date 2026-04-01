package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.Fn;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.provider.HelmProviderConfig;
import com.hashicorp.cdktf.providers.helm.provider.HelmProviderKubernetes;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.helm.release.ReleaseSet;
import com.hashicorp.cdktf.providers.kubernetes.deployment.Deployment;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

import static health.emrys.afon.cdk.AwsConstruct.AWS_POLARIS_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AWS;

public class AwsKubernetesPolaris extends TerraformStack implements EmrysCloud {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(AwsKubernetesPolaris.class);

    public static final SupportedCloudsEnum ID               = AWS;
    public static final String              POLARIS_INTERNAL = "polaris-internal";

    protected final PolarisConfig     polarisConfig;
    protected final AwsConstruct      cloudProvider;
    protected final Service           polarisLB;

    public AwsKubernetesPolaris(@NotNull Construct scope, SupportedCloudsEnum id) {
        super(scope, id.toString());
        NullProvider.Builder.create(this, "nullProvider")
                            .build();
        cloudProvider = new AwsConstruct(this, id.toString());
        polarisConfig = new PolarisConfig(this, "polaris", cloudProvider) {
            protected Service configurePolarisService(Deployment polarisDeployment, Map<String, String> polarisLabels) {
                return null; // no longer using an external service
            }
        };
        polarisLB     = polarisConfig.deployPolaris(cloudProvider.getDependables());
        Service.Builder.create(this, POLARIS_INTERNAL)
                       .metadata(ServiceMetadata.builder()
                                                .name(POLARIS_INTERNAL)
                                                .namespace("default")
                                                .build())
                       .dependsOn(cloudProvider.getDependables())
                       .spec(ServiceSpec.builder()
                                        .selector(polarisConfig.polarisLabels) // Selects the Pods created by secretAppDeployment
                                        .type("NodePort")
                                        .internalTrafficPolicy("Cluster")
//                                        .clusterIp("None")
                                        .clusterIps(List.of())
                                        .port(List.of(polarisConfig.POLARIS_PORTS))
                                        .build())
                       .build();
    }

    public static void main(String[] args) {
        final App app = new App();
        new AwsKubernetesPolaris(app, ID);
        app.synth();
        logger.warn("Don't forget to export KUBE_CONFIG_PATH=$HOME/.kube/config and unalias aws before running tofu -apply");
    }

    @Override
    public SupportedCloudsEnum getId() {
        return ID;
    }

    @Override
    public String getPolarisDomainName() {
        return AWS_POLARIS_DOMAIN_NAME;
    }
}
