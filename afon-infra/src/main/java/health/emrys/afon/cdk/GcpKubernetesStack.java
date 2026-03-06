package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import software.constructs.Construct;

class GcpKubernetesStack extends TerraformStack {

    public static final String ID = "gcp-k8s-polaris";
    protected final GcpConstruct cloudProvider;

    public GcpKubernetesStack(final Construct scope, final String id) {
        super(scope, id);
        NullProvider.Builder.create(this, "null").build();
        cloudProvider = new GcpConstruct(this, id, "afon-core");
    }

    public static void main(String[] args) {
        final App app = new App();
        new GcpKubernetesStack(app, ID);
        app.synth();
    }
}
