package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.App;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.StackId;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;


/**
 * Deploys only the KeyCloak server, ie the EKS cluster with KeyCloak behind its HTTPS ingress and
 * DNS record at {@code OIDC_DOMAIN_NAME}, without Polaris, eg to serve as a standalone OIDC provider, such as for
 * the {@link AwsPolarisStack}, which can run side by side with it.  Its egress IP is never pinned to
 * {@code AWS_EGRESS_IP}, which the Polaris stacks hold.
 */
public class AwsKeycloakStack extends AbstractAwsStack {

    protected final AwsKeyCloakConfig keyCloakConfig;

    public AwsKeycloakStack(@NonNull Construct scope,
                            StackId stackId,
                            AfonPropertyProvider properties) {
        super(scope, stackId, properties, false);
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
        new AwsKeycloakStack(app, StackId.of(AwsKeycloakStack.class), new FilePropertyProvider());
        app.synth();
    }

}
