package health.emrys.afon.cdk.azure;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.AppConfig;
import health.emrys.afon.cdk.AbstractCloudStack;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import software.constructs.Construct;

import static health.emrys.afon.config.AfonCloud.AZURE;
import static health.emrys.afon.config.ConfigUtils.cdktfDir;

class AzureKubernetesStack extends AbstractCloudStack {

    protected final AzureCloudProvider cloudProvider;
    protected final     String              azurePolarisDomainName;

    public AzureKubernetesStack(final Construct scope, final AfonCloud id,
                                AfonPropertyProvider properties) {
        super(scope, id);
        cloudProvider = new AzureCloudProvider(this, id.toString(), properties);
        this.azurePolarisDomainName = properties.getValue(AfonProperty.AZURE_DOMAIN_NAME);
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
        final App app = new App(AppConfig.builder()
                .outdir(cdktfDir())
                .build());
        new AzureKubernetesStack(app, AZURE, new FilePropertyProvider());
        app.synth();
    }

}