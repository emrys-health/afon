package health.emrys.afon.polaris.catalogs;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.polaris.catalogs.AwsUtils.bucketToUrl;

public class AwsForbiddenPolarisCatalog extends AwsPolarisCatalog {
    public AwsForbiddenPolarisCatalog(Properties properties) {
        super(AWS_S3_ROLE_ARN.getValueFrom(properties),
                bucketToUrl(AWS_S3_BUCKET_NAME_FORBIDDEN_BY_POLARIS.getValueFrom(properties)),
                AWS_REGION.getValueFrom(properties));
    }

    public boolean adminGrants() {
        return false;
    }

    public String catalogName() {
        return "aws_polaris_forbidden";
    }
}
