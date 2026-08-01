package health.emrys.afon.cdk.azure;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;
import health.emrys.afon.cdk.AfonCloudStack;
import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonProperties.AfonCloud;
import health.emrys.afon.config.AfonPropertiesLoader;
import software.constructs.Construct;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.AfonCloud.AZURE;

class AzureKubernetesStack extends TerraformStack implements AfonCloudStack {

    protected final AzureCloudProvider cloudProvider;
    protected final     String              azurePolarisDomainName;

    public AzureKubernetesStack(final Construct scope, final String id,
                                Properties properties) {
        super(scope, id);
        cloudProvider = new AzureCloudProvider(this, id, properties);
        this.azurePolarisDomainName = AfonProperties.AZURE_DOMAIN_NAME.getValueFrom(properties);
    }

    @Override
    public AfonCloud getId() {
        return AZURE;
    }

    @Override
    public String getPolarisDomainName() {
        return azurePolarisDomainName;
    }

    public static void main(String[] args) {
        final App app = new App();
        new AzureKubernetesStack(app, AZURE.toString(), new AfonPropertiesLoader().getProperties());
        app.synth();
    }

}