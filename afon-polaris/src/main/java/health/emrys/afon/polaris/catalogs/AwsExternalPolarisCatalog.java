package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonPropertyProvider;
import org.apache.polaris.core.admin.model.StorageConfigInfo;
import org.jspecify.annotations.NonNull;

import java.util.List;

import static health.emrys.afon.config.AfonCatalog.AWS;
import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.polaris.catalogs.AwsUtils.s3BucketUrl;

public class AwsExternalPolarisCatalog extends ExternalPolarisCatalog {
    private final String bucketUrl;
    private final String roleArn;
    private final String region;
    private final String endpoint;

    public AwsExternalPolarisCatalog(AfonPropertyProvider properties) {
        super(properties);
        this.bucketUrl = s3BucketUrl(properties.getValue(AWS_S3_EXTERNAL_BUCKET_NAME));
        this.roleArn = properties.getValue(AWS_S3_ROLE_ARN);
        this.region = properties.getValue(AWS_REGION);
        this.endpoint = "https://" + properties.getValue(AWS_POLARIS_DOMAIN_NAME);
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
        return endpoint;
    }

    @Override
    protected @NonNull String bucketUri() {
        return bucketUrl;
    }

    @Override
    protected @NonNull StorageConfigInfo storageConfigInfo() {
        return AwsUtils.createS3StorageConfigInfo(roleArn, bucketUrl, region);
    }

    @Override
    public String catalogName() {
        return AWS + "_EXTERNAL";
    }
}
