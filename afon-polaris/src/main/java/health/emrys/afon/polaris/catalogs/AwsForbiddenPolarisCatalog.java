package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonPropertyProvider;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.polaris.catalogs.AwsUtils.s3BucketUrl;

public class AwsForbiddenPolarisCatalog extends AwsPolarisCatalog {

    private final String bucketUrl;
    private final String roleArn;
    private final String region;
    public static final String AWS_FORBIDDEN = "aws_forbidden";

    public AwsForbiddenPolarisCatalog(AfonPropertyProvider properties) {
        super(properties);
        this.bucketUrl = s3BucketUrl(properties.getValue(AWS_S3_BUCKET_NAME_FORBIDDEN_BY_POLARIS));
        this.roleArn = properties.getValue(AWS_S3_ROLE_ARN);
        this.region  = properties.getValue(AWS_REGION);
    }

    public boolean adminGrants() {
        return false;
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

    public String catalogName() {
        return AWS_FORBIDDEN;
    }
}
