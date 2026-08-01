package health.emrys.afon.cdk.gcp;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import health.emrys.afon.cdk.AfonCloudStack;
import health.emrys.afon.config.AfonProperties.AfonCloud;
import health.emrys.afon.config.AfonPropertiesLoader;
import software.constructs.Construct;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.GCP_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperties.AfonCloud.GCP;

class GcpKubernetesStack extends TerraformStack implements AfonCloudStack {

    protected final GcpCloudProvider cloudProvider;
    protected final     String              gcpDomainName;
    private final       Properties          properties;

    public GcpKubernetesStack(final Construct scope, final String id, Properties properties) {
        super(scope, id);
        this.gcpDomainName = GCP_DOMAIN_NAME.getValueFrom(properties);
        this.properties    = properties;
        NullProvider.Builder.create(this, "null").build();
        cloudProvider = new GcpCloudProvider(this, id, "afon-core", properties);
    }

    public static void main(String[] args) {
        final App  app        = new App();
        Properties properties = new AfonPropertiesLoader().getProperties();
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
