package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;
import health.emrys.afon.cdk.aws.AwsCloudProvider;
import health.emrys.afon.cdk.azure.AzureCloudProvider;
import health.emrys.afon.cdk.component.KeycloakComponent;
import health.emrys.afon.cdk.component.PolarisComponent;
import health.emrys.afon.cdk.component.StackComponent;
import health.emrys.afon.cdk.component.StackContext;
import health.emrys.afon.cdk.gcp.GcpCloudProvider;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.StackId;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.constructs.Construct;

import static health.emrys.afon.cdk.StackDefinitions.isKeyCloakExternal;

/**
 * A stack composed of the {@link StackComponent}s of its {@link StackDefinition}, deployed in order to the cloud of its
 * {@link StackId}, each taking the config of that cloud.
 */
public class ComposedStack extends AbstractCloudStack {

    private static final Logger logger = LoggerFactory.getLogger(ComposedStack.class);

    private final StackContext context;

    public ComposedStack(@NonNull Construct scope, @NonNull StackDefinition definition, AfonPropertyProvider properties) {
        super(scope, definition.id());
        StackId       stackId  = definition.id();
        CloudPlatform platform = platformOf(this, definition, properties);
        context = new StackContext(this, stackId, platform, properties);
        definition.components().apply(properties).forEach(context::deploy);
        if (context.find(KeycloakComponent.class).isEmpty() && context.find(PolarisComponent.class).isPresent()) {
            logger.info("Stack {} deploys no KeyCloak, but uses the one at {} ({}), which the aws-keycloak stack deploys{}",
                        stackId, properties.getValue(AfonProperty.OIDC_DOMAIN_NAME), AfonProperty.OIDC_DOMAIN_NAME,
                        stackId.cloud() == AfonCloud.AWS && isKeyCloakExternal(properties)
                        ? ", as configured by " + AfonProperty.AWS_KEYCLOAK_EXTERNAL + "=true" : "");
        }
    }

    private static CloudPlatform platformOf(TerraformStack stack, StackDefinition definition, AfonPropertyProvider properties) {
        return switch (definition.id().cloud()) {
            case AWS -> AwsCloudProvider.bootstrap(stack, definition.id(), properties, definition.pinnedEgressIp());
            case AZURE -> AzureCloudProvider.bootstrap(stack, definition.id(), properties);
            case GCP -> GcpCloudProvider.bootstrap(stack, definition.id(), properties);
        };
    }

    /**
     * @return what the stack was deployed with, eg to find the components deployed
     */
    public StackContext context() {
        return context;
    }

    @Override
    public AfonCloud getId() {
        return context.stackId().cloud();
    }

    /**
     * @return the domain name Polaris is served at, or {@code null} if the stack deploys no Polaris
     */
    @Override
    public String getPolarisDomainName() {
        return context.find(PolarisComponent.class).map(PolarisComponent::domainName).orElse(null);
    }

    /**
     * Synthesises the plan of the stack into its dir, see {@link health.emrys.afon.config.ConfigUtils#stackDir}, without
     * deploying it, eg to check that the config dir has all the properties the stack needs.
     */
    public static ComposedStack synthesise(StackId stackId, AfonPropertyProvider properties) {
        App app = createApp();
        ComposedStack stack = new ComposedStack(app, StackDefinitions.get(stackId), properties);
        app.synth();
        return stack;
    }

    /**
     * @param args the id of the stack, eg {@code aws-full}
     */
    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: ComposedStack <stack-id>, eg aws-full");
        }
        synthesise(StackId.parse(args[0]), new FilePropertyProvider());
    }
}
