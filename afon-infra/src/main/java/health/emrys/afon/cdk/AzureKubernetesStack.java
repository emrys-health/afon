package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;
import software.constructs.Construct;

import static health.emrys.afon.cdk.AzureKubernetesPolaris.AZURE_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AZURE;

class AzureKubernetesStack extends TerraformStack implements EmrysCloud {

    public static final String ID = AZURE.toString();
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

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getPolarisDomainName() {
        return AZURE_DOMAIN_NAME;
    }
}