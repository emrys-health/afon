package health.emrys.afon.cdk;

import com.hashicorp.cdktf.LocalBackend;
import com.hashicorp.cdktf.LocalBackendConfig;
import com.hashicorp.cdktf.TerraformStack;
import health.emrys.afon.config.AfonCloud;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import static health.emrys.afon.config.ConfigUtils.tfStatePath;

public abstract class AbstractCloudStack extends TerraformStack implements AfonCloudStack {
    private String localBackendPath;

    public AbstractCloudStack(@NotNull Construct scope, @NotNull AfonCloud id) {
        super(scope, id.toString());
        configureLocalBackend(id);
    }

    private void configureLocalBackend(@NonNull AfonCloud id) {
        this.localBackendPath = tfStatePath(id);
        new LocalBackend(this,
                LocalBackendConfig.builder()
                        .path(this.localBackendPath)
                        .build());
    }

    protected String getLocalBackendPath() { return localBackendPath; }
}
