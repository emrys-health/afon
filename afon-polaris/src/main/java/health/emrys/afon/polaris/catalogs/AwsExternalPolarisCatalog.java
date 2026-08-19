package health.emrys.afon.polaris.catalogs;

import org.apache.polaris.core.admin.model.StorageConfigInfo;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.config.AfonProperty.AWS_REGION;
import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.polaris.catalogs.AwsUtils.bucketToUrl;

public class AwsExternalPolarisCatalog extends ExternalPolarisCatalog {
    private final String bucketUrl;
    private final String roleArn;
    private final String region;

    public AwsExternalPolarisCatalog(Properties properties) {
        super(properties);
        this.bucketUrl = bucketToUrl(AWS_S3_EXTERNAL_BUCKET_NAME.getValueFrom(properties));
        this.roleArn = AWS_S3_ROLE_ARN.getValueFrom(properties);
        this.region = AWS_REGION.getValueFrom(properties);
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
