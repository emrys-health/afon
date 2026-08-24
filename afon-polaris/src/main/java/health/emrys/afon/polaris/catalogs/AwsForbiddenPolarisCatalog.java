package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonPropertyProvider;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.polaris.catalogs.AwsUtils.bucketToUrl;

public class AwsForbiddenPolarisCatalog extends AwsPolarisCatalog {

    public AwsForbiddenPolarisCatalog(AfonPropertyProvider properties) {
        super(properties.getValue(AWS_S3_ROLE_ARN),
                bucketToUrl(properties.getValue(AWS_S3_BUCKET_NAME_FORBIDDEN_BY_POLARIS)),
                properties.getValue(AWS_REGION));
    }

    public boolean adminGrants() {
        return false;
    }

    public String catalogName() {
        return "aws_polaris_forbidden";
    }
}
