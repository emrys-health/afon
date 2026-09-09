package health.emrys.afon.cdk.gcp;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.AppConfig;
import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import health.emrys.afon.cdk.AbstractCloudStack;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.config.FilePropertyProvider;
import software.constructs.Construct;

import static health.emrys.afon.config.AfonCloud.GCP;
import static health.emrys.afon.config.AfonProperty.GCP_POLARIS_DOMAIN_NAME;

class GcpKubernetesStack extends AbstractCloudStack {

    protected final GcpCloudProvider cloudProvider;
    protected final String gcpDomainName;
    private final AfonPropertyProvider properties;

    public GcpKubernetesStack(final Construct scope, final AfonCloud id, AfonPropertyProvider properties) {
        super(scope, id);
        this.gcpDomainName = properties.getValue(GCP_POLARIS_DOMAIN_NAME);
        this.properties    = properties;
        NullProvider.Builder.create(this, "null").build();
        cloudProvider = new GcpCloudProvider(this, id.toString(), "afon-core", properties);
    }

    @Override
    public AfonCloud getId() {
        return GCP;
    }

    @Override
    public String getPolarisDomainName() {
        return gcpDomainName;
    }

    public static void main(String[] args) {
        final App app = createApp();
        AfonPropertyProvider properties = new FilePropertyProvider();
        new GcpKubernetesStack(app, GCP, properties);
        app.synth();
    }

}
