package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.App;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import static health.emrys.afon.config.AfonCloud.AWS;

/**
 * Deploys only the KeyCloak server, ie the EKS cluster with KeyCloak behind its HTTPS ingress and
 * DNS record at {@code OIDC_DOMAIN_NAME}, without Polaris, eg to serve as a standalone OIDC provider.
 */
public class AwsKeycloakStack extends AbstractAwsStack {

    protected final AwsKeyCloakConfig keyCloakConfig;

    public AwsKeycloakStack(@NonNull Construct scope,
                            AfonCloud id,
                            AfonPropertyProvider properties) {
        super(scope, id, properties);
        keyCloakConfig = new AwsKeyCloakConfig(this, properties, cloudProvider);
        keyCloakConfig.configureHttpsAndDNS();
    }

    /**
     * @return {@code null}, as this stack deploys no Polaris
     */
    @Override
    public String getPolarisDomainName() {
        return null;
    }

    public static void main(String[] args) {
        final App app = createApp();
        new AwsKeycloakStack(app, AWS, new FilePropertyProvider());
        app.synth();
    }

}
