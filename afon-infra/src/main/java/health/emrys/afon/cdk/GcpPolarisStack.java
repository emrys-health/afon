package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import org.slf4j.Logger;
import software.constructs.Construct;

import java.util.List;

public class GcpPolarisStack extends TerraformStack {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(AwsWholeStack.class);

    public static final String ID     = "gcp-polaris-deployment";

    public GcpPolarisStack(final Construct scope, final String id) {
        super(scope, id);

        NullProvider.Builder.create(this, "nullProvider")
                            .build();

        CloudProvider cloudProvider = new GcpConstruct(this, id, "afon-core");
        PolarisConfig polarisConfig = new PolarisConfig(this, "polaris", cloudProvider);
        polarisConfig.deployPolaris(List.of());
    }

    public static void main(String[] args) {
        logger.warn("You may need to first run: gcloud auth application-default login before running tofu apply -auto-approve");
        final App app = new App();
        new GcpPolarisStack(app, ID);
        app.synth();
    }
}
