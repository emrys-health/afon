package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.Aspects;
import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import health.emrys.afon.cdk.AbstractCloudStack;
import health.emrys.afon.cdk.KubeConfigAspect;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.StackId;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.Map;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.AfonProperty.AWS_KEYCLOAK_EXTERNAL;
import static health.emrys.afon.config.ConfigUtils.kubeConfigPath;

/**
 * Abstract base class for the AWS stacks, which configures what every AWS stack needs:
 * the null provider, the {@link AwsCloudProvider} with its Helm provider, and the pinned
 * provider versions.
 */
public abstract class AbstractAwsStack extends AbstractCloudStack {

    protected final AwsCloudProvider cloudProvider;

    /**
     * @param pinnedEgressIp whether the stack's egress IP is pinned to {@code AWS_EGRESS_IP}, if set, which only
     *                       one running stack can hold, else the stack allocates its own
     */
    protected AbstractAwsStack(@NonNull Construct scope,
                               StackId stackId,
                               AfonPropertyProvider properties,
                               boolean pinnedEgressIp) {
        super(scope, stackId);
        NullProvider.Builder.create(this, "nullProvider")
                            .build();
        cloudProvider = new AwsCloudProvider(this, stackId.cloud().toString(), properties, stackId, pinnedEgressIp);
        cloudProvider.configureHelm();
        // the stack's kubectl and helm commands only ever act on its own cluster
        Aspects.of(this).add(new KubeConfigAspect(kubeConfigPath(stackId)));
        pinProviderVersions();
    }

    /**
     * @return whether the stacks otherwise deploying their own KeyCloak, eg {@link AwsFullStack}, use instead the one
     * at {@code OIDC_DOMAIN_NAME} that the {@link AwsKeycloakStack} deploys, as configured by
     * {@code AWS_KEYCLOAK_EXTERNAL}, {@code false} when unset
     */
    public static boolean isKeyCloakExternal(AfonPropertyProvider properties) {
        return Boolean.parseBoolean(properties.getValue(AWS_KEYCLOAK_EXTERNAL, "false").trim());
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
