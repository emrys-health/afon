package health.emrys.afon.polaris.catalogs;

import org.apache.polaris.core.admin.model.StorageConfigInfo;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.config.AfonProperties.AWS_REGION;
import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.polaris.catalogs.AwsUtils.bucketToUrl;

public class ExternalAwsCloudConfigForPolaris extends ExternalCloudConfigForPolaris {
    private final String bucketUrl;
    private final String roleArn;
    private final String region;

    public ExternalAwsCloudConfigForPolaris(Properties properties) {
        super(properties);
        this.bucketUrl = bucketToUrl(AWS_S3_EXTERNAL_BUCKET_NAME.getValueFrom(properties));
        this.roleArn = AWS_S3_ROLE_ARN.getValueFrom(properties);
        this.region = AWS_REGION.getValueFrom(properties);
    }

    @Override
    protected String getRemoteCatalogName() {
        return AWS.toString();
    }

    @Override
    protected List<String> getScopes() {
        return List.of();
    }

    @Override
    protected @NonNull String getEndpoint() {
        return "https://emryspolaris.click";
    }

    @Override
    protected @NonNull String getBucketUri() {
        return bucketUrl;
    }

    @Override
    protected @NonNull StorageConfigInfo getStorageConfigInfo() {
        return AwsUtils.getStorageConfigInfo(roleArn, bucketUrl, region);
    }

    @Override
    public String warehouseName() {
        return AWS + "_EXTERNAL";
    }
}
