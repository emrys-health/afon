package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;
import software.constructs.Construct;

import java.util.List;

class AksKubernetesStack extends TerraformStack {
    public AksKubernetesStack(final Construct scope, final String id) {
        super(scope, id);

        AzureConstruct cloudProvider = new AzureConstruct(this, id);
        PolarisConfig polarisConfig = new PolarisConfig(this, "polaris", cloudProvider);
        polarisConfig.deployPolaris(List.of());
    }

    public static void main(String[] args) {
        final App app = new App();
        new AksKubernetesStack(app, "az-polaris");
        app.synth();
    }
}