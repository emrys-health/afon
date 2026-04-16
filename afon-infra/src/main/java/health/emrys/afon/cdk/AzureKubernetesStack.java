package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.config.AfonPropertiesLoader;
import software.constructs.Construct;

import java.util.Properties;

import static health.emrys.afon.cdk.AzureKubernetesPolaris.AZURE_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AZURE;

class AzureKubernetesStack extends TerraformStack implements EmrysCloud {

    public static final SupportedCloudsEnum ID = AZURE;
    protected final     AzureConstruct      cloudProvider;

    public AzureKubernetesStack(final Construct scope, final String id,
                                Properties properties) {
        super(scope, id);
        cloudProvider = new AzureConstruct(this, id, properties);
    }

    public static void main(String[] args) {
        final App app = new App();
        new AzureKubernetesStack(app, ID.toString(), new AfonPropertiesLoader().getProperties());
        app.synth();
    }

    @Override
    public SupportedCloudsEnum getId() {
        return ID;
    }

    @Override
    public String getPolarisDomainName() {
        return AZURE_DOMAIN_NAME;
    }
}