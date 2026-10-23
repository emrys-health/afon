package health.emrys.afon.polaris.catalogs;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import org.apache.polaris.core.admin.model.StorageConfigInfo;
import org.jspecify.annotations.NonNull;

import java.util.List;

import static health.emrys.afon.config.AfonProperty.AWS_POLARIS_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperty.AWS_REGION;
import static health.emrys.afon.config.AfonProperty.AWS_S3_ROLE_ARN;
import static health.emrys.afon.polaris.catalogs.AwsUtils.s3BucketUrl;

public class AwsExternalPolarisCatalog extends ExternalPolarisCatalog {
    private final String catalogName;
    private final String bucketUrl;
    private final String roleArn;
    private final String region;
    private final String endpoint;

    /**
     * Builds any "external"-typed AWS catalog purely from its {@link AfonCatalog} definition;
     * credentials, region and domain are fixed per cloud and read here directly.
     */
    public AwsExternalPolarisCatalog(AfonCatalog catalog, AfonPropertyProvider properties) {
        super(properties, AfonCloud.AWS);
        this.catalogName = catalog.toString();
        this.bucketUrl = s3BucketUrl(catalog.bucketName());
        this.roleArn = properties.getValue(AWS_S3_ROLE_ARN);
        this.region = properties.getValue(AWS_REGION);
        this.endpoint = "https://" + properties.getValue(AWS_POLARIS_DOMAIN_NAME);
    }

    @Override
    protected String remoteCatalogName() {
        return "aws";
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
        return catalogName;
    }
}
