package health.emrys.afon.polaris.catalogs;

import org.apache.polaris.core.admin.model.*;

import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.*;

public class AwsCloudConfigForPolaris extends CloudConfigForPolaris {

    private final String warehouseName;
    private final String roleArn;
    private final String bucket;
    private final String region;


    public AwsCloudConfigForPolaris(Properties properties) {
        super(new JsonMaker());
        this.warehouseName = AWS_WAREHOUSE_NAME.getValueFrom(properties);
        this.roleArn = AWS_S3_ROLE_ARN.getValueFrom(properties);
        this.bucket = AWS_S3_BUCKET_NAME.getValueFrom(properties);
        this.region = AWS_REGION.getValueFrom(properties);
    }

    @Override
    public String catalogConfig() throws IncorrectConfigException {
        CatalogProperties catalogProperties = new CatalogProperties(bucket);
        StorageConfigInfo storageConfigInfo = new AwsStorageConfigInfo(
                roleArn,
                null,
                null,
                region,
                StorageConfigInfo.StorageTypeEnum.S3,
                List.of(bucket)
        );
        Catalog catalog = new Catalog(
                Catalog.TypeEnum.INTERNAL,
                warehouseName,
                catalogProperties,
                storageConfigInfo
        );
        Catalogs catalogs = new Catalogs(List.of(catalog));
        return toJson(catalogs);
    }

    @Override
    public String warehouseName() {
        return warehouseName;
    }

    @Override
    public String toString() {
        return "AwsCloudConfigForPolaris{" +
                "warehouseName='" + warehouseName + '\'' +
                ", roleArn='" + roleArn + '\'' +
                ", bucket='" + bucket + '\'' +
                ", region='" + region + '\'' +
                '}';
    }
}
