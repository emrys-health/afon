package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonPropertyProvider;

import static health.emrys.afon.config.AfonProperty.AWS_S3_BUCKET_NAME_TRINO;
import static health.emrys.afon.polaris.catalogs.AwsUtils.s3BucketUrl;

public class AwsTrinoPolarisCatalog extends AwsPolarisCatalog {

    // Only bucketUri() needs overriding: AwsPolarisCatalog#storageConfigInfo() reads its own
    // roleArn/region fields directly rather than through overridable accessors.
    private final String bucketUrl;

    public AwsTrinoPolarisCatalog(AfonPropertyProvider properties) {
        super(properties);
        this.bucketUrl = s3BucketUrl(properties.getValue(AWS_S3_BUCKET_NAME_TRINO));
    }

    @Override
    protected String bucketUri() {
        return bucketUrl;
    }

    @Override
    public String catalogName() {
        return "aws_trino";
    }
}
