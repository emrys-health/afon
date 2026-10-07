package health.emrys.afon.cdk;

import com.hashicorp.cdktf.*;

import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.config.StackId;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import software.constructs.Construct;

import static health.emrys.afon.config.ConfigUtils.tfStatePath;

/**
 * Abstract base class for the stacks, each named by its {@link StackId}, eg {@code aws-keycloak}, so that
 * each stack has its own plan dir {@link ConfigUtils#stackDir(StackId)} and tofu state
 * {@link ConfigUtils#tfStatePath(StackId)}.
 */
public abstract class AbstractCloudStack extends TerraformStack implements AfonCloudStack {
    private final static Logger logger = org.slf4j.LoggerFactory.getLogger(AbstractCloudStack.class);

    private final StackId stackId;
    private String localBackendPath;

    public AbstractCloudStack(@NotNull Construct scope, @NotNull StackId stackId) {
        super(scope, stackId.toString());
        this.stackId = stackId;
        configureLocalBackend(stackId);
    }

    protected static @NonNull App createApp() {
        return new App(AppConfig.builder()
                .outdir(ConfigUtils.afonCkdtf())
                .build());
    }

    @Override
    public StackId getStackId() {
        return stackId;
    }

    private void configureLocalBackend(@NonNull StackId stackId) {
        this.localBackendPath = tfStatePath(stackId);
        logger.info("Using local backend {}", localBackendPath);
        new LocalBackend(this,
                LocalBackendConfig.builder()
                        .path(this.localBackendPath)
                        .build());
    }

}
