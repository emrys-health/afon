package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonPropertyProvider;
import org.apache.polaris.core.admin.model.StorageConfigInfo;

import static health.emrys.afon.config.AfonCatalog.AWS;
import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.polaris.catalogs.AwsUtils.s3BucketUrl;

public class AwsPolarisCatalog extends PolarisCatalog {

    private final String bucketUrl;
    private final String roleArn;
    private final String region;

    public AwsPolarisCatalog(AfonPropertyProvider properties) {
        this.bucketUrl = s3BucketUrl(properties.getValue(AWS_S3_BUCKET_NAME));
        this.roleArn = properties.getValue(AWS_S3_ROLE_ARN);
        this.region  = properties.getValue(AWS_REGION);
    }

    @Override
    public String catalogName() {
        return AWS.toString();
    }

    @Override
    protected StorageConfigInfo storageConfigInfo() {
        return AwsUtils.createS3StorageConfigInfo(roleArn, bucketUri(), region);
    }

    protected String bucketUri() {
        return bucketUrl;
    }

    protected String roleArn() {
        return roleArn;
    }

    protected String region() {
        return region;
    }

    @Override
    public String toString() {
        return String.format("%s[catalogName=%s, bucketUri=%s, roleArn=%s, region=%s]",
                this.getClass().getSimpleName(),
                catalogName(), bucketUri(), roleArn(), region());
    }


}
