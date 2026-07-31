package health.emrys.afon.polaris.catalogs;

import org.apache.polaris.core.admin.model.Catalog;
import org.apache.polaris.core.admin.model.CreateCatalogRequest;
import org.apache.polaris.core.admin.model.StorageConfigInfo;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.afon.polaris.catalogs.AwsUtils.bucketToUrl;

public class AwsCloudConfigForPolaris extends CloudConfigForPolaris {

    private final String roleArn;
    private final String bucket;
    private final String region;

    protected AwsCloudConfigForPolaris(String roleArn, String bucket, String region) {
        super(new JsonMaker());
        this.roleArn = roleArn;
        this.bucket  = bucket;
        this.region  = region;
    }

    public AwsCloudConfigForPolaris(Properties properties) {
        this(
            AWS_S3_ROLE_ARN.getValueFrom(properties),
            bucketToUrl(AWS_S3_BUCKET_NAME.getValueFrom(properties)),
            AWS_REGION.getValueFrom(properties));
    }

    @Override
    public String catalogConfig() throws IncorrectConfigException {
        Catalog catalog = createCatalog();
        return toJson(new CreateCatalogRequest(catalog));
    }

    @Override
    public String warehouseName() {
        return AfonCloud.AWS.toString();
    }

    @Override
    protected StorageConfigInfo getStorageConfigInfo() {
        return AwsUtils.getStorageConfigInfo(roleArn, getBucketUri(), region);
    }

    public String getRoleArn() {
        return roleArn;
    }

    protected String getBucketUri() {
        return bucket;
    }

    public String getRegion() {
        return region;
    }

    @Override
    public String toString() {
        return "AwsCloudConfigForPolaris{" +
               "warehouseName='" + warehouseName() + '\'' +
               ", roleArn='" + roleArn + '\'' +
               ", bucket='" + getBucketUri() + '\'' +
               ", region='" + region + '\'' +
               '}';
    }
}
