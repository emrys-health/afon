package health.emrys.afon.cdk.azure;

import com.hashicorp.cdktf.App;
import health.emrys.afon.cdk.AbstractCloudStack;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.StackId;
import software.constructs.Construct;

import static health.emrys.afon.config.AfonCloud.AZURE;

class AzureKubernetesStack extends AbstractCloudStack {

    protected final AzureCloudProvider cloudProvider;
    protected final     String              azurePolarisDomainName;

    public AzureKubernetesStack(final Construct scope, final StackId stackId,
                                AfonPropertyProvider properties) {
        super(scope, stackId);
        cloudProvider = new AzureCloudProvider(this, stackId.cloud().toString(), properties);
        this.azurePolarisDomainName = properties.getValue(AfonProperty.AZURE_POLARIS_DOMAIN_NAME);
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
        final App app = createApp();
        new AzureKubernetesStack(app, StackId.of(AzureKubernetesStack.class), new FilePropertyProvider());
        app.synth();
    }

}