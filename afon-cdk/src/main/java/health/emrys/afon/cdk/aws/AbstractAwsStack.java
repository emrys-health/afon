package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import health.emrys.afon.cdk.AbstractCloudStack;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.Map;

import static health.emrys.afon.config.AfonCloud.AWS;

/**
 * Abstract base class for the AWS stacks, which configures what every AWS stack needs:
 * the null provider, the {@link AwsCloudProvider} with its Helm provider, and the pinned
 * provider versions.
 */
public abstract class AbstractAwsStack extends AbstractCloudStack {

    protected final AwsCloudProvider cloudProvider;

    protected AbstractAwsStack(@NonNull Construct scope,
                               AfonCloud id,
                               AfonPropertyProvider properties) {
        super(scope, id);
        NullProvider.Builder.create(this, "nullProvider")
                            .build();
        cloudProvider = new AwsCloudProvider(this, id.toString(), properties);
        cloudProvider.configureHelm();
        pinProviderVersions();
    }

    /**
     * Pins the exact versions of the providers every AWS stack uses.
     */
    private void pinProviderVersions() {
        this.addOverride(
            "terraform.required_providers.aws", Map.of(
                "source", "hashicorp/aws",
                "version", "6.27.0"   // 👈 Pin exact version
                                                      ));
        this.addOverride(
            "terraform.required_providers.helm", Map.of(
                "source", "hashicorp/helm",
                "version", "3.2.0"   // 👈 Pin exact version
                                                       ));
        this.addOverride(
            "terraform.required_providers.kubernetes", Map.of(
                "source", "hashicorp/kubernetes",
                // 3.2.1 fixes "Unexpected Identity Change" errors when a resource takes
                // extended time to become ready (as happened when an EKS token expired
                // mid-rollout of oauth2-proxy). Do not downgrade below 3.2.1.
                "version", "3.2.1"   // 👈 Pin exact version
                                                             ));
    }

    @Override
    public AfonCloud getId() {
        return AWS;
    }

}
