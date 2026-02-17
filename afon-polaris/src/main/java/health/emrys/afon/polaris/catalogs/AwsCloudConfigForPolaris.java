package health.emrys.afon.polaris.catalogs;

import org.apache.polaris.core.admin.model.*;

import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;

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
        this(AWS_S3_ROLE_ARN.getValueFrom(properties),
             "s3a://" + AWS_S3_BUCKET_NAME.getValueFrom(properties),
             AWS_REGION.getValueFrom(properties));
    }

    @Override
    public String catalogConfig() throws IncorrectConfigException {
        Catalog catalog = createCatalog(bucket, getStorageConfigInfo());
        return toJson(new CreateCatalogRequest(catalog));
    }

    public AwsStorageConfigInfo getStorageConfigInfo() {
        return AwsStorageConfigInfo
                .builder()
                .setRoleArn(roleArn)
                .setAllowedLocations(List.of(bucket))
                .setRegion(region)
                .setStorageType(StorageConfigInfo.StorageTypeEnum.S3)
                .build();
    }

    @Override
    public String warehouseName() {
        return SupportedCloudsEnum.AWS.toString();
    }

    public String getRoleArn() {
        return roleArn;
    }

    public String getBucket() {
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
                ", bucket='" + bucket + '\'' +
                ", region='" + region + '\'' +
                '}';
    }
}
