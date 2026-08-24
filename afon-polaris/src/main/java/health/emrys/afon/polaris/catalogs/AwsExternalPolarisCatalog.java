package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonPropertyProvider;
import org.apache.polaris.core.admin.model.StorageConfigInfo;
import org.jspecify.annotations.NonNull;

import java.util.List;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.polaris.catalogs.AwsUtils.bucketToUrl;

public class AwsExternalPolarisCatalog extends ExternalPolarisCatalog {
    private final String bucketUrl;
    private final String roleArn;
    private final String region;

    public AwsExternalPolarisCatalog(AfonPropertyProvider properties) {
        super(properties);
        this.bucketUrl = bucketToUrl(properties.getValue(AWS_S3_EXTERNAL_BUCKET_NAME));
        this.roleArn = properties.getValue(AWS_S3_ROLE_ARN);
        this.region = properties.getValue(AWS_REGION);
    }

    @Override
    protected String remoteCatalogName() {
        return AWS.toString();
    }

    @Override
    protected List<String> scopes() {
        return List.of();
    }

    @Override
    protected @NonNull String endpoint() {
        return "https://emryspolaris.click";
    }

    @Override
    protected @NonNull String bucketUri() {
        return bucketUrl;
    }

    @Override
    protected @NonNull StorageConfigInfo storageConfigInfo() {
        return AwsUtils.getStorageConfigInfo(roleArn, bucketUrl, region);
    }

    @Override
    public String catalogName() {
        return AWS + "_EXTERNAL";
    }
}
