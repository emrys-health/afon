package health.emrys.afon.cdk.gcp;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import health.emrys.afon.cdk.AfonCloudStack;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import software.constructs.Construct;

import static health.emrys.afon.config.AfonProperty.GCP_DOMAIN_NAME;
import static health.emrys.afon.config.AfonCloud.GCP;

class GcpKubernetesStack extends TerraformStack implements AfonCloudStack {

    protected final GcpCloudProvider cloudProvider;
    protected final     String              gcpDomainName;
    private final       AfonPropertyProvider properties;

    public GcpKubernetesStack(final Construct scope, final String id, AfonPropertyProvider properties) {
        super(scope, id);
        this.gcpDomainName = properties.getValue(GCP_DOMAIN_NAME);
        this.properties    = properties;
        NullProvider.Builder.create(this, "null").build();
        cloudProvider = new GcpCloudProvider(this, id, "afon-core", properties);
    }

    public static void main(String[] args) {
        final App  app        = new App();
        AfonPropertyProvider properties = new FilePropertyProvider();
        new GcpKubernetesStack(app, GCP.toString(), properties);
        app.synth();
    }

    @Override
    public AfonCloud getId() {
        return GCP;
    }

    @Override
    public String getPolarisDomainName() {
        return gcpDomainName;
    }
}
