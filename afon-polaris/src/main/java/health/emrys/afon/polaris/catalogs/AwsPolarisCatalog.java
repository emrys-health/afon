package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import org.apache.polaris.core.admin.model.StorageConfigInfo;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.polaris.catalogs.AwsUtils.bucketToUrl;

public class AwsPolarisCatalog extends PolarisCatalog {

    private final String roleArn;
    private final String bucket;
    private final String region;

    protected AwsPolarisCatalog(String roleArn, String bucket, String region) {
        this.roleArn = roleArn;
        this.bucket  = bucket;
        this.region  = region;
    }

    public AwsPolarisCatalog(AfonPropertyProvider properties) {
        this(properties.getValue(AWS_S3_ROLE_ARN),
            bucketToUrl(properties.getValue(AWS_S3_BUCKET_NAME)),
                    properties.getValue(AWS_REGION));
    }

    @Override
    public String catalogName() {
        return AfonCloud.AWS.toString();
    }

    @Override
    protected StorageConfigInfo storageConfigInfo() {
        return AwsUtils.getStorageConfigInfo(roleArn, bucketUri(), region);
    }

    public String getRoleArn() {
        return roleArn;
    }

    protected String bucketUri() {
        return bucket;
    }

    public String getRegion() {
        return region;
    }

    @Override
    public String toString() {
        return "AwsPolarisCatalog{" +
               "warehouseName='" + catalogName() + '\'' +
               ", roleArn='" + roleArn + '\'' +
               ", bucket='" + bucketUri() + '\'' +
               ", region='" + region + '\'' +
               '}';
    }
}
