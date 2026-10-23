package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.AppConfig;
import com.hashicorp.cdktf.LocalBackend;
import com.hashicorp.cdktf.LocalBackendConfig;
import com.hashicorp.cdktf.TerraformStack;
import health.emrys.afon.cdk.aws.AwsCloudProvider;
import health.emrys.afon.cdk.azure.AzureCloudProvider;
import health.emrys.afon.cdk.components.KeycloakComponent;
import health.emrys.afon.cdk.components.PolarisComponent;
import health.emrys.afon.cdk.gcp.GcpCloudProvider;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.config.FileUserProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.StackId;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.constructs.Construct;

import static health.emrys.afon.cdk.StackDefinitions.isKeyCloakExternal;
import static health.emrys.afon.config.ConfigUtils.tfStatePath;

/**
 * A stack composed of the {@link StackComponent}s of its {@link StackDefinition}, deployed in order to the cloud of its
 * {@link StackId}, each taking the config of that cloud.  Each stack has its own plan dir
 * {@link ConfigUtils#stackDir(StackId)} and tofu state {@link ConfigUtils#tfStatePath(StackId)}.
 */
public class ComposedStack extends TerraformStack {

    private static final Logger logger = LoggerFactory.getLogger(ComposedStack.class);

    private final StackContext context;

    public ComposedStack(@NonNull Construct scope, @NonNull StackDefinition definition, AfonPropertyProvider properties,
                         AfonUserProvider users) {
        super(scope, definition.id().toString());
        StackId       stackId  = definition.id();
        configureLocalBackend(stackId);
        CloudPlatform platform = platformOf(this, definition, properties);
        context = new StackContext(this, stackId, platform, properties, users);
        definition.componentsFor(properties).forEach(context::deploy);
        if (context.find(KeycloakComponent.class).isEmpty() && context.find(PolarisComponent.class).isPresent()) {
            logger.info("Stack {} deploys no KeyCloak, but uses the one at {} ({}), which the {}-keycloak stack deploys{}",
                        stackId, properties.getOidcDomainName(stackId.cloud()), properties.getOidcDomainProperty(stackId.cloud()),
                        stackId.cloud(), isKeyCloakExternal(stackId.cloud(), properties)
                        ? ", as configured by " + stackId.cloud().toString().toUpperCase() + "_KEYCLOAK_EXTERNAL=true" : "");
        }
    }

    private static CloudPlatform platformOf(TerraformStack stack, StackDefinition definition, AfonPropertyProvider properties) {
        return switch (definition.id().cloud()) {
            case AWS -> AwsCloudProvider.bootstrap(stack, definition.id(), properties, definition.pinnedEgressIp());
            case AZURE -> AzureCloudProvider.bootstrap(stack, definition.id(), properties, definition.pinnedEgressIp());
            case GCP -> GcpCloudProvider.bootstrap(stack, definition.id(), properties, definition.pinnedEgressIp());
        };
    }

    private void configureLocalBackend(StackId stackId) {
        String localBackendPath = tfStatePath(stackId);
        logger.info("Using local backend {}", localBackendPath);
        new LocalBackend(this,
                         LocalBackendConfig.builder()
                                           .path(localBackendPath)
                                           .build());
    }

    /**
     * @return what the stack was deployed with, eg to find the components deployed
     */
    public StackContext context() {
        return context;
    }

    /**
     * Synthesises the plan of the stack into its dir, see {@link health.emrys.afon.config.ConfigUtils#stackDir}, without
     * deploying it, eg to check that the config dir has all the properties the stack needs.
     */
    public static ComposedStack synthesise(StackId stackId, AfonPropertyProvider properties, AfonUserProvider users) {
        App app = new App(AppConfig.builder().outdir(ConfigUtils.afonCkdtf()).build());
        ComposedStack stack = new ComposedStack(app, StackDefinitions.get(stackId), properties, users);
        app.synth();
        return stack;
    }

    /**
     * @param args the id of the stack, {@code <stack-id>}
     */
    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: ComposedStack <stack-id>, eg aws-full");
        }
        synthesise(StackDefinitions.resolve(StackId.parse(args[0])), new FilePropertyProvider(), new FileUserProvider());
    }
}
