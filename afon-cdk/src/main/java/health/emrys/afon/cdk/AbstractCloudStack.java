package health.emrys.afon.cdk;

import com.hashicorp.cdktf.*;
import health.emrys.afon.cdk.aws.AwsSparkStack;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.ConfigUtils;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import software.constructs.Construct;

import static health.emrys.afon.config.AfonProperty.AWS_REGION;
import static health.emrys.afon.config.AfonProperty.TF_STATE_BUCKET;
import static health.emrys.afon.config.AfonProperty.TF_STATE_KEY;
import static health.emrys.afon.config.AfonProperty.TF_STATE_KMS_KEY;
import static health.emrys.afon.config.ConfigUtils.tfStatePath;

public abstract class AbstractCloudStack extends TerraformStack implements AfonCloudStack {
    private final static Logger logger = org.slf4j.LoggerFactory.getLogger(AwsSparkStack.class);

    private String localBackendPath;

    public AbstractCloudStack(@NotNull Construct scope, @NotNull AfonCloud id) {
        super(scope, id.toString());
        configureLocalBackend(id);
    }

    /**
     * HDRS (Patch 13): S3 remote state instead of CDKTF's default local backend.
     * The local default writes terraform.&lt;stack&gt;.tfstate into the working
     * directory - a single file, not backed up, with NO locking, so two
     * concurrent runs silently corrupt each other's view and losing it means
     * deleting every resource by hand.
     *
     * <p>Same bucket as the HDRS infra state: versioned, SSE-KMS, public access
     * blocked. That matters here because this state contains credentials in
     * plaintext.
     *
     * <p>Falls back to upstream's local backend when TF_STATE_BUCKET is unset,
     * so nothing changes for a deployment that has not opted in.
     */
    public AbstractCloudStack(@NotNull Construct scope, @NotNull AfonCloud id, @NotNull AfonPropertyProvider properties) {
        super(scope, id.toString());
        // The bucket DEFAULTS to the HDRS state bucket rather than gating on being
        // set. An empty default here would silently fall back to a local backend,
        // and a local backend against a live stack is not a no-op: tofu would see
        // no state, believe nothing exists, and plan to create the whole estate
        // again. The config carries no TF_STATE_* keys, so the default is what
        // actually runs.
        String bucket = properties.getValue(TF_STATE_BUCKET, "hdrs-tfstate-euw2-804799611298");
        // AfonCloud.AWS.toString() is lowercase "aws", so this resolves to the live
        // key afon-core/aws.tfstate.
        String key = properties.getValue(TF_STATE_KEY, "afon-core/" + id + ".tfstate");
        logger.info("Using S3 backend s3://{}/{}", bucket, key);
        // use_lockfile (native S3 locking, no DynamoDB) is set via addOverride:
        // cdktf 0.21's S3BackendConfig predates it and only exposes
        // dynamodbTable. Needs OpenTofu >= 1.10.
        S3Backend backend = new S3Backend(this, S3BackendConfig.builder()
            .bucket(bucket)
            .key(key)
            .region(properties.getValue(AWS_REGION))
            .encrypt(true)
            .kmsKeyId(properties.getValue(TF_STATE_KMS_KEY, "alias/hdrs-tfstate"))
            .build());
        backend.addOverride("use_lockfile", true);
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
