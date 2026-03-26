package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;
import health.emrys.afon.config.AfonProperties;
import software.constructs.Construct;

import static health.emrys.afon.cdk.AzureKubernetesPolaris.AZURE_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AZURE;

class AzureKubernetesStack extends TerraformStack implements EmrysCloud {

    public static final AfonProperties.SupportedCloudsEnum ID = AZURE;
    protected final     AzureConstruct                     cloudProvider;

    public AzureKubernetesStack(final Construct scope, final String id) {
        super(scope, id);
        cloudProvider = new AzureConstruct(this, id);
    }

    public static void main(String[] args) {
        final App app = new App();
        new AzureKubernetesStack(app, ID.toString());
        app.synth();
    }

    @Override
    public AfonProperties.SupportedCloudsEnum getId() {
        return ID;
    }

    @Override
    public String getPolarisDomainName() {
        return AZURE_DOMAIN_NAME;
    }
}