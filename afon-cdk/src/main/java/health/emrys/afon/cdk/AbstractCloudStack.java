package health.emrys.afon.cdk;

import com.hashicorp.cdktf.*;
import health.emrys.afon.cdk.aws.AwsSparkStack;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.ConfigUtils;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import software.constructs.Construct;

import static health.emrys.afon.config.ConfigUtils.tfStatePath;

public abstract class AbstractCloudStack extends TerraformStack implements AfonCloudStack {
    private final static Logger logger = org.slf4j.LoggerFactory.getLogger(AwsSparkStack.class);

    private String localBackendPath;

    public AbstractCloudStack(@NotNull Construct scope, @NotNull AfonCloud id) {
        super(scope, id.toString());
        configureLocalBackend(id);
    }

    protected static @NonNull App createApp() {
        return new App(AppConfig.builder()
                .outdir(ConfigUtils.afonCkdtf())
                .build());
    }

    private void configureLocalBackend(@NonNull AfonCloud id) {
        this.localBackendPath = tfStatePath(id);
        logger.info("Using local backend {}", localBackendPath);
        new LocalBackend(this,
                LocalBackendConfig.builder()
                        .path(this.localBackendPath)
                        .build());
    }

}
