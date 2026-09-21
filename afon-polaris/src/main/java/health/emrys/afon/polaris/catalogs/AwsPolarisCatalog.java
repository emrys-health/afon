package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonPropertyProvider;
import org.apache.polaris.core.admin.model.StorageConfigInfo;

import static health.emrys.afon.config.AfonProperty.AWS_REGION;
import static health.emrys.afon.config.AfonProperty.AWS_S3_ROLE_ARN;
import static health.emrys.afon.polaris.catalogs.AwsUtils.s3BucketUrl;

public class AwsPolarisCatalog extends PolarisCatalog {

    private final String catalogName;
    private final boolean adminGrants;
    private final String bucketUrl;
    private final String roleArn;
    private final String region;

    /**
     * Builds any "internal"-typed AWS catalog purely from its {@link AfonCatalog} definition;
     * credentials and region are fixed per cloud and read here directly.
     */
    public AwsPolarisCatalog(AfonCatalog catalog, AfonPropertyProvider properties) {
        this.catalogName = catalog.toString();
        this.adminGrants = catalog.adminGrants();
        this.bucketUrl = s3BucketUrl(catalog.bucketName());
        this.roleArn = properties.getValue(AWS_S3_ROLE_ARN);
        this.region  = properties.getValue(AWS_REGION);
    }

    @Override
    public String catalogName() {
        return catalogName;
    }

    @Override
    public boolean adminGrants() {
        return adminGrants;
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
