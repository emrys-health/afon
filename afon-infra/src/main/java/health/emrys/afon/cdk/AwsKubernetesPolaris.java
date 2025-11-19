package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import software.constructs.Construct;

public class AwsKubernetesPolaris extends TerraformStack {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(AwsKubernetesPolaris.class);

    protected static final String ID = "jupyter-deployment";

    protected final PolarisConfig     polarisConfig;
    protected final AbstractConstruct cloudProvider;
    protected final Service           polarisLB;

    public AwsKubernetesPolaris(@NotNull Construct scope, @NotNull String id) {
        super(scope, id);
        NullProvider.Builder.create(this, "nullProvider")
                            .build();

        cloudProvider = new AwsConstruct(this, id);
        polarisConfig = new PolarisConfig(this, "polaris", cloudProvider);
        polarisLB     = polarisConfig.deployPolaris(cloudProvider.getDependables());

    }

    public static void main(String[] args) {
        final App app = new App();
        new AwsKubernetesPolaris(app, ID);
        app.synth();
        logger.warn("Don't forget to export KUBE_CONFIG_PATH=$HOME/.kube/config and unalias aws before running tofu -apply");
    }
}
