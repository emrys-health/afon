package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import org.slf4j.Logger;
import software.constructs.Construct;

import java.util.List;

public class GcpPolarisStack extends TerraformStack {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(WholeStack.class);

    public static final String ID     = "polaris-deployment";

    public GcpPolarisStack(final Construct scope, final String id) {
        super(scope, id);

        NullProvider.Builder.create(this, "nullProvider")
                            .build();

        CloudProvider cloudProvider = new GcpConstruct(this, id, "afon-core");
        PolarisConfig polarisConfig = new PolarisConfig(this, "polaris", cloudProvider);
        polarisConfig.deployPolaris(List.of());
    }

    public static void main(String[] args) {
        final App app = new App();
        new GcpPolarisStack(app, ID);
        app.synth();
    }
}
