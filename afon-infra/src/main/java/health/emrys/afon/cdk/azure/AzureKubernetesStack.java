package health.emrys.afon.cdk.azure;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;
import health.emrys.afon.cdk.EmrysCloud;
import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.config.AfonPropertiesLoader;
import software.constructs.Construct;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AZURE;

class AzureKubernetesStack extends TerraformStack implements EmrysCloud {

    public static final SupportedCloudsEnum ID = AZURE;
    protected final     AzureConstruct      cloudProvider;
    protected final     String              azurePolarisDomainName;

    public AzureKubernetesStack(final Construct scope, final String id,
                                Properties properties) {
        super(scope, id);
        cloudProvider = new AzureConstruct(this, id, properties);
        this.azurePolarisDomainName = AfonProperties.AZURE_DOMAIN_NAME.getValueFrom(properties);
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
        return azurePolarisDomainName;
    }
}