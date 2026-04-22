package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.config.AfonPropertiesLoader;
import software.constructs.Construct;

import java.util.Properties;

import static health.emrys.afon.cdk.GcpKubernetesPolaris.GCP_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.GCP;

class GcpKubernetesStack extends TerraformStack implements EmrysCloud {

    public static final SupportedCloudsEnum ID = GCP;
    protected final     GcpConstruct        cloudProvider;
    private final Properties properties;

    public GcpKubernetesStack(final Construct scope, final String id, Properties properties) {
        super(scope, id);
        this.properties = properties;
        NullProvider.Builder.create(this, "null").build();
        cloudProvider = new GcpConstruct(this, id, "afon-core", properties);
    }

    public static void main(String[] args) {
        final App app = new App();
        Properties properties = new AfonPropertiesLoader().getProperties();
        new GcpKubernetesStack(app, ID.toString(), properties);
        app.synth();
    }

    @Override
    public SupportedCloudsEnum getId() {
        return ID;
    }

    @Override
    public String getPolarisDomainName() {
        return GCP_DOMAIN_NAME;
    }
}
