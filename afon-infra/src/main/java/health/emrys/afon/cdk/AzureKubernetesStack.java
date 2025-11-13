package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;
import software.constructs.Construct;

class AzureKubernetesStack extends TerraformStack {

    public static final String ID = "az-polaris";
    protected final AzureConstruct cloudProvider;

    public AzureKubernetesStack(final Construct scope, final String id) {
        super(scope, id);
        cloudProvider = new AzureConstruct(this, id);
    }

    public static void main(String[] args) {
        final App app = new App();
        new AzureKubernetesStack(app, ID);
        app.synth();
    }
}