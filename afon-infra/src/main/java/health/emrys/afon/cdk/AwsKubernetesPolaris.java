package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.kubernetes.deployment.Deployment;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

public class AwsKubernetesPolaris extends TerraformStack {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(AwsKubernetesPolaris.class);

    protected static final String ID               = "jupyter-deployment";
    public static final    String POLARIS_INTERNAL = "polaris-internal";

    protected final PolarisConfig     polarisConfig;
    protected final AwsConstruct      cloudProvider;
    protected final Service           polarisLB;

    public AwsKubernetesPolaris(@NotNull Construct scope, @NotNull String id) {
        super(scope, id);
        NullProvider.Builder.create(this, "nullProvider")
                            .build();
        // HelmProvider.Builder.create(this, "helm")
        //                     .build();

        cloudProvider = new AwsConstruct(this, id);
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
}
